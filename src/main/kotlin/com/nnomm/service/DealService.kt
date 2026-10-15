package com.nnomm.service

import com.nnomm.domain.*
import com.nnomm.repository.DealRepository
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

object DealService {

    suspend fun createDeal(user: AuthUser, req: CreateDealRequest): DealResponse {
        // 1. Role verification: Only restaurant_admin can create deals
        if (user.role != UserRole.RESTAURANT_ADMIN || user.restaurantId == null) {
            throw ApiException(ErrorCode.FORBIDDEN, "Only restaurant admins can create deals")
        }

        // 2. Title validation: 1–100 characters
        val title = req.title?.trim()
        if (title.isNullOrEmpty() || title.length > 100) {
            throw ApiException(ErrorCode.VALIDATION_FAILED, "Title must be between 1 and 100 characters")
        }

        // 3. Price validation: 1 to 1,000,000 paise
        val pricePaise = req.price_paise
        if (pricePaise == null || pricePaise < 1 || pricePaise > 1_000_000) {
            throw ApiException(ErrorCode.VALIDATION_FAILED, "Price must be between 1 and 1,000,000 paise")
        }

        // 4. Stock validation: 1 to 10,000 units
        val stockTotal = req.stock_total
        if (stockTotal == null || stockTotal < 1 || stockTotal > 10_000) {
            throw ApiException(ErrorCode.VALIDATION_FAILED, "Stock total must be between 1 and 10,000")
        }

        // 5. Time window validation: ISO-8601 UTC and starts_at before ends_at
        val startsAt = parseInstant(req.starts_at, "starts_at")
        val endsAt = parseInstant(req.ends_at, "ends_at")

        if (!startsAt.isBefore(endsAt)) {
            throw ApiException(ErrorCode.VALIDATION_FAILED, "starts_at must be before ends_at")
        }

        // Ensure parent restaurant exists in DB
        DealRepository.ensureRestaurantExists(user.restaurantId)

        return DealRepository.createDeal(
            restaurantId = user.restaurantId,
            title = title,
            pricePaise = pricePaise,
            stockTotal = stockTotal,
            startsAt = startsAt,
            endsAt = endsAt
        )
    }

    suspend fun getDeal(user: AuthUser, dealIdStr: String): DealResponse {
        val dealId = try {
            UUID.fromString(dealIdStr)
        } catch (e: Exception) {
            throw ApiException(ErrorCode.NOT_FOUND, "Deal not found")
        }

        return DealRepository.findById(dealId)
            ?: throw ApiException(ErrorCode.NOT_FOUND, "Deal not found")
    }

    private fun parseInstant(dateStr: String?, fieldName: String): Instant {
        if (dateStr.isNullOrBlank()) {
            throw ApiException(ErrorCode.VALIDATION_FAILED, "Field '$fieldName' is required")
        }
        return try {
            Instant.parse(dateStr)
        } catch (e: DateTimeParseException) {
            throw ApiException(ErrorCode.VALIDATION_FAILED, "Field '$fieldName' must be a valid ISO 8601 timestamp")
        }
    }
}
