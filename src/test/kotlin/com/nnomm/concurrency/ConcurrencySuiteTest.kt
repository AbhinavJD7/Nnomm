package com.nnomm.concurrency

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.nnomm.config.AppConfig
import com.nnomm.config.DatabaseFactory
import com.nnomm.domain.*
import com.nnomm.repository.ClaimRepository
import com.nnomm.repository.DealRepository
import com.nnomm.repository.WebhookRepository
import com.nnomm.security.HmacService
import com.nnomm.service.ClaimExecutionResult
import com.nnomm.service.ClaimService
import com.nnomm.service.PaymentWebhookService
import com.nnomm.worker.ClaimExpiryWorker
import kotlinx.coroutines.*
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID

@Testcontainers
class ConcurrencySuiteTest {

    companion object {
        @Container
        val postgres = PostgreSQLContainer("postgres:15-alpine").apply {
            withDatabaseName("nnomm_deals_test")
            withUsername("test_user")
            withPassword("test_pass")
        }

        @BeforeAll
        @JvmStatic
        fun setup() {
            postgres.start()
            DatabaseFactory.init(
                jdbcUrl = postgres.jdbcUrl,
                username = postgres.username,
                password = postgres.password
            )
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            DatabaseFactory.close()
            postgres.stop()
        }
    }

    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()
    private val webhookSecret = AppConfig.webhookSecret

    private suspend fun createTestDeal(stockTotal: Int = 50, pricePaise: Int = 10000): DealResponse {
        val restId = UUID.randomUUID()
        DealRepository.ensureRestaurantExists(restId)
        return DealRepository.createDeal(
            restaurantId = restId,
            title = "Concurrency Test Deal",
            pricePaise = pricePaise,
            stockTotal = stockTotal,
            startsAt = Instant.now().minusSeconds(100),
            endsAt = Instant.now().plusSeconds(7200)
        )
    }

    /**
     * Scenario 1:
     * 200 concurrent claims against 50 items:
     * - Exactly 50 succeed (201 Created)
     * - Exactly 150 receive 409 SOLD_OUT
     * - stock_available reaches exactly 0 (No overselling invariant)
     */
    @Test
    fun `scenario 1 - 200 concurrent claims against 50 items results in exactly 50 successes and 0 stock`() = runBlocking {
        val deal = createTestDeal(stockTotal = 50)
        val dealId = deal.id
        val totalAttempts = 200

        val results = (1..totalAttempts).map { index ->
            val customerId = UUID.randomUUID()
            val user = AuthUser(userId = customerId, role = UserRole.CUSTOMER)
            val idempotencyKey = "key-sc1-$index-${UUID.randomUUID()}"
            val req = ClaimRequest(quantity = 1)

            async(Dispatchers.IO) {
                runCatching {
                    ClaimService.createClaim(
                        user = user,
                        dealIdStr = dealId.toString(),
                        idempotencyKeyHeader = idempotencyKey,
                        req = req
                    )
                }
            }
        }.awaitAll()

        val successes = results.filter { it.isSuccess && it.getOrThrow() is ClaimExecutionResult.Created }
        val soldOutErrors = results.filter {
            val ex = it.exceptionOrNull()
            ex is ApiException && ex.errorCode == ErrorCode.SOLD_OUT
        }

        assertEquals(50, successes.size, "Exactly 50 claims must succeed")
        assertEquals(150, soldOutErrors.size, "Exactly 150 claims must fail with SOLD_OUT")

        val finalDeal = DealRepository.findById(dealId)!!
        assertEquals(0, finalDeal.stock_available, "stock_available must reach exactly 0")
        assertEquals(50, finalDeal.stock_total, "stock_total remains unchanged")
    }

    /**
     * Scenario 2:
     * 20 concurrent requests with identical Idempotency-Key:
     * - Exactly 1 claim created
     * - All 20 receive identical claim ID (via atomic lock + retry replay)
     * - Stock decremented by exactly 1
     */
    @Test
    fun `scenario 2 - 20 concurrent requests with identical Idempotency-Key return identical claim and decrement stock once`() = runBlocking {
        val deal = createTestDeal(stockTotal = 10)
        val customerId = UUID.randomUUID()
        val user = AuthUser(userId = customerId, role = UserRole.CUSTOMER)
        val sharedKey = "shared-idempotency-key-${UUID.randomUUID()}"
        val req = ClaimRequest(quantity = 1)

        val results = (1..20).map {
            async(Dispatchers.IO) {
                // Client retry loop: if request is in progress, backoff slightly until completed
                var attempt = 0
                var resolvedClaimId: UUID? = null
                while (attempt < 40 && resolvedClaimId == null) {
                    try {
                        when (val res = ClaimService.createClaim(user, deal.id.toString(), sharedKey, req)) {
                            is ClaimExecutionResult.Created -> resolvedClaimId = res.claim.id
                            is ClaimExecutionResult.Replayed -> {
                                val claim = objectMapper.readValue(res.body, ClaimResponse::class.java)
                                resolvedClaimId = claim.id
                            }
                        }
                    } catch (e: ApiException) {
                        if (e.errorCode == ErrorCode.REQUEST_IN_PROGRESS) {
                            delay(25)
                            attempt++
                        } else {
                            throw e
                        }
                    }
                }
                checkNotNull(resolvedClaimId) { "Failed to resolve claim within retry limit" }
            }
        }.awaitAll()

        assertEquals(20, results.size, "All 20 requests must successfully resolve")
        val distinctClaimIds = results.distinct()
        assertEquals(1, distinctClaimIds.size, "All 20 requests must return the identical claim ID")

        val finalDeal = DealRepository.findById(deal.id)!!
        assertEquals(9, finalDeal.stock_available, "Stock must only be decremented once (10 -> 9)")
    }

    /**
     * Scenario 3:
     * Concurrent claims by one customer with different keys create max 1 claim
     * - Enforced by PostgreSQL partial unique index
     * - Exactly 1 succeeds, remaining fail with 409 ALREADY_CLAIMED
     */
    @Test
    fun `scenario 3 - concurrent claims by one customer with different keys create max 1 active claim`() = runBlocking {
        val deal = createTestDeal(stockTotal = 10)
        val customerId = UUID.randomUUID()
        val user = AuthUser(userId = customerId, role = UserRole.CUSTOMER)
        val totalAttempts = 10

        val results = (1..totalAttempts).map { index ->
            val uniqueKey = "customer-key-$index-${UUID.randomUUID()}"
            val req = ClaimRequest(quantity = 1)
            async(Dispatchers.IO) {
                runCatching {
                    ClaimService.createClaim(
                        user = user,
                        dealIdStr = deal.id.toString(),
                        idempotencyKeyHeader = uniqueKey,
                        req = req
                    )
                }
            }
        }.awaitAll()

        val successes = results.filter { it.isSuccess && it.getOrThrow() is ClaimExecutionResult.Created }
        val alreadyClaimedErrors = results.filter {
            val ex = it.exceptionOrNull()
            ex is ApiException && ex.errorCode == ErrorCode.ALREADY_CLAIMED
        }

        assertEquals(1, successes.size, "Exactly 1 claim can be created per customer per deal")
        assertEquals(9, alreadyClaimedErrors.size, "All other concurrent attempts must fail with ALREADY_CLAIMED")

        val finalDeal = DealRepository.findById(deal.id)!!
        assertEquals(9, finalDeal.stock_available, "Stock must only be decremented once")
    }

    /**
     * Scenario 4:
     * Webhook edge cases:
     * - Bad HMAC signature rejected (401)
     * - Stale timestamp (> 300s drift) rejected (401)
     * - Duplicate event ID deduplicated without side effects
     * - Out-of-order event handled gracefully (e.g., unknown claim acknowledged without crash)
     */
    @Test
    fun `scenario 4 - payment webhook rejects stale timestamp and bad signatures, deduplicates events`() = runBlocking {
        val deal = createTestDeal(stockTotal = 5)
        val customerId = UUID.randomUUID()
        val user = AuthUser(userId = customerId, role = UserRole.CUSTOMER)
        val claimResult = ClaimService.createClaim(user, deal.id.toString(), "sc4-key-${UUID.randomUUID()}", ClaimRequest(1))
        val claim = (claimResult as ClaimExecutionResult.Created).claim

        val eventId = "evt-edge-${UUID.randomUUID()}"
        val eventPayload = """
            {
                "event_id": "$eventId",
                "type": "payment.succeeded",
                "claim_id": "${claim.id}",
                "amount_paise": 10000,
                "occurred_at": "${Instant.now()}"
            }
        """.trimIndent()
        val payloadBytes = eventPayload.toByteArray(Charsets.UTF_8)

        // 1. Stale timestamp (> 300s in past)
        val staleTimestamp = Instant.now().minusSeconds(350).epochSecond
        val staleHeader = HmacService.generateSignatureHeader(payloadBytes, webhookSecret, staleTimestamp)
        val staleEx = assertThrows<ApiException> {
            PaymentWebhookService.handleWebhook(staleHeader, payloadBytes)
        }
        assertEquals(ErrorCode.UNAUTHENTICATED, staleEx.errorCode)

        // 2. Bad signature
        val badHeader = "t=${Instant.now().epochSecond},v1=deadbeef12345678"
        val badSigEx = assertThrows<ApiException> {
            PaymentWebhookService.handleWebhook(badHeader, payloadBytes)
        }
        assertEquals(ErrorCode.UNAUTHENTICATED, badSigEx.errorCode)

        // 3. Valid first delivery
        val validHeader = HmacService.generateSignatureHeader(payloadBytes, webhookSecret)
        PaymentWebhookService.handleWebhook(validHeader, payloadBytes)

        val updatedClaim = ClaimRepository.findById(claim.id)!!
        assertEquals(ClaimStatus.PAID, updatedClaim.status)
        assertTrue(WebhookRepository.isEventProcessed(eventId), "Event must be recorded in processed_webhooks")

        // 4. Duplicate delivery of the exact same event
        PaymentWebhookService.handleWebhook(validHeader, payloadBytes)
        val claimAfterDuplicate = ClaimRepository.findById(claim.id)!!
        assertEquals(ClaimStatus.PAID, claimAfterDuplicate.status, "Claim status should remain PAID without errors")

        // 5. Out-of-order / unknown claim event handled cleanly
        val unknownEventId = "evt-unknown-${UUID.randomUUID()}"
        val unknownPayload = """
            {
                "event_id": "$unknownEventId",
                "type": "payment.succeeded",
                "claim_id": "${UUID.randomUUID()}",
                "amount_paise": 10000,
                "occurred_at": "${Instant.now()}"
            }
        """.trimIndent()
        val unknownBytes = unknownPayload.toByteArray(Charsets.UTF_8)
        val unknownHeader = HmacService.generateSignatureHeader(unknownBytes, webhookSecret)

        PaymentWebhookService.handleWebhook(unknownHeader, unknownBytes)
        assertTrue(WebhookRepository.isEventProcessed(unknownEventId), "Unknown claim event should be recorded as processed")
    }

    /**
     * Scenario 5:
     * Simultaneous race between payment webhook and expiry worker on the same claim:
     * - Row-level locking (SELECT ... FOR UPDATE) ensures atomic serialization
     * - If payment wins: Claim becomes PAID, stock remains decremented
     * - If expiry wins: Claim becomes EXPIRED and stock is restored; late payment transitions to REFUND_PENDING without re-deducting stock
     * - In all cases, the stock invariant is strictly preserved
     */
    @Test
    fun `scenario 5 - simultaneous race between payment webhook and expiry worker preserves stock invariant`() = runBlocking {
        val deal = createTestDeal(stockTotal = 1)
        val customerId = UUID.randomUUID()
        val user = AuthUser(userId = customerId, role = UserRole.CUSTOMER)
        val claimResult = ClaimService.createClaim(user, deal.id.toString(), "sc5-key-${UUID.randomUUID()}", ClaimRequest(1))
        val claim = (claimResult as ClaimExecutionResult.Created).claim

        // Artificially age the claim so it is past expires_at
        DatabaseFactory.query { conn ->
            val updateSql = "UPDATE claims SET expires_at = NOW() - INTERVAL '10 seconds' WHERE id = ?;"
            conn.prepareStatement(updateSql).use { stmt ->
                stmt.setObject(1, claim.id)
                stmt.executeUpdate()
            }
        }

        val eventId = "evt-race-${UUID.randomUUID()}"
        val eventPayload = """
            {
                "event_id": "$eventId",
                "type": "payment.succeeded",
                "claim_id": "${claim.id}",
                "amount_paise": 10000,
                "occurred_at": "${Instant.now()}"
            }
        """.trimIndent()
        val payloadBytes = eventPayload.toByteArray(Charsets.UTF_8)
        val validHeader = HmacService.generateSignatureHeader(payloadBytes, webhookSecret)

        // Launch both simultaneously
        val expiryJob = async(Dispatchers.IO) {
            ClaimExpiryWorker.expireOverdueClaims()
        }
        val webhookJob = async(Dispatchers.IO) {
            PaymentWebhookService.handleWebhook(validHeader, payloadBytes)
        }

        expiryJob.await()
        webhookJob.await()

        val finalClaim = ClaimRepository.findById(claim.id)!!
        val finalDeal = DealRepository.findById(deal.id)!!

        // The claim MUST be either PAID or REFUND_PENDING
        assertTrue(
            finalClaim.status == ClaimStatus.PAID || finalClaim.status == ClaimStatus.REFUND_PENDING,
            "Claim must be either PAID or REFUND_PENDING, was: ${finalClaim.status}"
        )

        if (finalClaim.status == ClaimStatus.PAID) {
            // Payment won race: stock remains consumed (0)
            assertEquals(0, finalDeal.stock_available, "If payment won race, stock remains consumed (0)")
        } else {
            // Expiry won race: claim expired first, restoring stock to 1. Late payment safely set to REFUND_PENDING
            assertEquals(1, finalDeal.stock_available, "If expiry won race, stock was restored to 1")
        }
    }

    /**
     * Scenario 6:
     * Customer and Restaurant visibility isolation:
     * - Customer cannot read another customer's claim (returns 404 NOT_FOUND, never 403 or data leak)
     * - Restaurant admin cannot read claims for another restaurant's deal (returns 404 NOT_FOUND)
     * - Legitimate owners (claiming customer and deal's restaurant admin) can read the claim
     */
    @Test
    fun `scenario 6 - claim visibility isolation returns 404 for unauthorized customers and admins`() = runBlocking {
        val restAId = UUID.randomUUID()
        val restBId = UUID.randomUUID()
        DealRepository.ensureRestaurantExists(restAId)
        DealRepository.ensureRestaurantExists(restBId)

        val dealA = DealRepository.createDeal(
            restaurantId = restAId,
            title = "Restaurant A Deal",
            pricePaise = 5000,
            stockTotal = 5,
            startsAt = Instant.now().minusSeconds(10),
            endsAt = Instant.now().plusSeconds(3600)
        )

        val customer1Id = UUID.randomUUID()
        val customer2Id = UUID.randomUUID()
        val customer1 = AuthUser(userId = customer1Id, role = UserRole.CUSTOMER)
        val customer2 = AuthUser(userId = customer2Id, role = UserRole.CUSTOMER)

        val adminA = AuthUser(userId = UUID.randomUUID(), role = UserRole.RESTAURANT_ADMIN, restaurantId = restAId)
        val adminB = AuthUser(userId = UUID.randomUUID(), role = UserRole.RESTAURANT_ADMIN, restaurantId = restBId)

        val claimResult = ClaimService.createClaim(customer1, dealA.id.toString(), "sc6-key-${UUID.randomUUID()}", ClaimRequest(1))
        val claim = (claimResult as ClaimExecutionResult.Created).claim

        // 1. Owner customer can access
        val ownerRead = ClaimService.getClaim(customer1, claim.id.toString())
        assertEquals(claim.id, ownerRead.id)

        // 2. Intruder customer gets 404 NOT_FOUND (anti-enumeration)
        val intruderEx = assertThrows<ApiException> {
            ClaimService.getClaim(customer2, claim.id.toString())
        }
        assertEquals(ErrorCode.NOT_FOUND, intruderEx.errorCode)

        // 3. Restaurant A admin (deal owner) can access
        val adminARead = ClaimService.getClaim(adminA, claim.id.toString())
        assertEquals(claim.id, adminARead.id)

        // 4. Restaurant B admin (other restaurant) gets 404 NOT_FOUND
        val adminBEx = assertThrows<ApiException> {
            ClaimService.getClaim(adminB, claim.id.toString())
        }
        assertEquals(ErrorCode.NOT_FOUND, adminBEx.errorCode)
    }
}
