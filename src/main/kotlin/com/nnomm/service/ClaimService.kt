package com.nnomm.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.nnomm.config.AppConfig
import com.nnomm.domain.*
import com.nnomm.repository.ClaimRepository
import com.nnomm.repository.IdempotencyRepository
import java.util.UUID

sealed class ClaimExecutionResult {
    data class Created(val claim: ClaimResponse) : ClaimExecutionResult()
    data class Replayed(val statusCode: Int, val body: String) : ClaimExecutionResult()
}

object ClaimService {

    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()
    private val idempotencyKeyRegex = Regex("^[A-Za-z0-9_-]{1,64}$")

    suspend fun createClaim(
        user: AuthUser,
        dealIdStr: String,
        idempotencyKeyHeader: String?,
        req: ClaimRequest
    ): ClaimExecutionResult {
        // 1. Roles: Only customers claim deals
        if (user.role != UserRole.CUSTOMER) {
            throw ApiException(ErrorCode.FORBIDDEN, "Only customers can claim deals")
        }

        // 2. Idempotency-Key validation: 1-64 chars from A-Z, a-z, 0-9, -, _
        val key = idempotencyKeyHeader?.trim()
        if (key.isNullOrBlank() || !idempotencyKeyRegex.matches(key)) {
            throw ApiException(ErrorCode.IDEMPOTENCY_KEY_INVALID, "Missing or malformed Idempotency-Key header")
        }

        // 3. Quantity limits: 1–3 units
        val quantity = req.quantity
        if (quantity == null || quantity < 1 || quantity > 3) {
            throw ApiException(ErrorCode.VALIDATION_FAILED, "Quantity must be between 1 and 3 units")
        }

        // 4. Validate dealId UUID
        val dealId = try {
            UUID.fromString(dealIdStr)
        } catch (e: Exception) {
            throw ApiException(ErrorCode.NOT_FOUND, "Deal not found")
        }

        // 5. Idempotency pipeline check: Acquire or Replay
        val acquisitionResult = IdempotencyRepository.acquireOrReplay(
            customerId = user.userId,
            key = key,
            dealId = dealId,
            quantity = quantity
        )

        if (acquisitionResult is IdempotencyRepository.KeyAcquisitionResult.Replay) {
            return ClaimExecutionResult.Replayed(
                statusCode = acquisitionResult.statusCode,
                body = acquisitionResult.body
            )
        }

        // 6. Execute claim transaction atomically
        try {
            val claim = ClaimRepository.executeClaimTransaction(
                dealId = dealId,
                customerId = user.userId,
                quantity = quantity,
                claimTtlSeconds = AppConfig.claimTtlSeconds
            )

            // Store successful outcome in idempotency record
            val responseBody = objectMapper.writeValueAsString(claim)
            IdempotencyRepository.saveOutcome(
                customerId = user.userId,
                key = key,
                statusCode = 201,
                responseBody = responseBody
            )

            return ClaimExecutionResult.Created(claim)
        } catch (e: ApiException) {
            // Rule: Store 409 conflicts (SOLD_OUT, ALREADY_CLAIMED, DEAL_NOT_LIVE) in idempotency records
            if (e.errorCode == ErrorCode.SOLD_OUT ||
                e.errorCode == ErrorCode.ALREADY_CLAIMED ||
                e.errorCode == ErrorCode.DEAL_NOT_LIVE
            ) {
                val errorBody = objectMapper.writeValueAsString(e.toErrorResponse())
                IdempotencyRepository.saveOutcome(
                    customerId = user.userId,
                    key = key,
                    statusCode = e.errorCode.httpStatus.value,
                    responseBody = errorBody
                )
            }
            throw e
        }
    }

    suspend fun getClaim(user: AuthUser, claimIdStr: String): ClaimResponse {
        val claimId = try {
            UUID.fromString(claimIdStr)
        } catch (e: Exception) {
            throw ApiException(ErrorCode.NOT_FOUND, "Claim not found")
        }

        val claimInfo = ClaimRepository.findClaimWithDealInfo(claimId)
            ?: throw ApiException(ErrorCode.NOT_FOUND, "Claim not found")

        val (claim, restaurantId) = claimInfo

        // Rule: Visibility - a claim is visible only to its customer and to the admin of the deal's restaurant. Anyone else gets 404, not 403.
        val isOwner = (user.role == UserRole.CUSTOMER && user.userId == claim.customer_id)
        val isDealAdmin = (user.role == UserRole.RESTAURANT_ADMIN && user.restaurantId == restaurantId)

        if (!isOwner && !isDealAdmin) {
            throw ApiException(ErrorCode.NOT_FOUND, "Claim not found")
        }

        return claim
    }
}
