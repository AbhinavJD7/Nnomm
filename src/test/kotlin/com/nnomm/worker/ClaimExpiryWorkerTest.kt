package com.nnomm.worker

import com.nnomm.config.DatabaseFactory
import com.nnomm.domain.ClaimStatus
import com.nnomm.repository.ClaimRepository
import com.nnomm.repository.DealRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID

@Testcontainers
class ClaimExpiryWorkerTest {

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

    @Test
    fun `worker expires overdue claims and restores stock atomically`() = runBlocking {
        val restId = UUID.randomUUID()
        DealRepository.ensureRestaurantExists(restId)
        val deal = DealRepository.createDeal(
            restaurantId = restId,
            title = "Expiry Worker Test Deal",
            pricePaise = 10000,
            stockTotal = 10,
            startsAt = Instant.now().minusSeconds(100),
            endsAt = Instant.now().plusSeconds(3600)
        )

        // Claim 1: Expired (negative TTL: -5 seconds)
        val expiredClaim = ClaimRepository.executeClaimTransaction(
            dealId = deal.id,
            customerId = UUID.randomUUID(),
            quantity = 3,
            claimTtlSeconds = -5L
        )

        // Claim 2: Active (positive TTL: +600 seconds)
        val activeClaim = ClaimRepository.executeClaimTransaction(
            dealId = deal.id,
            customerId = UUID.randomUUID(),
            quantity = 2,
            claimTtlSeconds = 600L
        )

        // Initial stock: 10 - 3 - 2 = 5
        val dealBefore = DealRepository.findById(deal.id)
        assertEquals(5, dealBefore?.stock_available)

        // Run worker expiry pass
        val count = ClaimExpiryWorker.expireOverdueClaims()
        assertEquals(1, count, "Should have expired exactly 1 claim")

        // Deal stock restored: 5 + 3 = 8
        val dealAfter = DealRepository.findById(deal.id)
        assertEquals(8, dealAfter?.stock_available, "Stock should be restored by 3 units")

        // Check claim statuses in DB directly
        val claim1 = ClaimRepository.findClaimWithDealInfo(expiredClaim.id)?.first
        val claim2 = ClaimRepository.findClaimWithDealInfo(activeClaim.id)?.first

        assertEquals(ClaimStatus.EXPIRED, claim1?.status)
        assertEquals(ClaimStatus.RESERVED, claim2?.status)

        // Running a second pass should find 0 overdue claims and not change stock
        val secondPassCount = ClaimExpiryWorker.expireOverdueClaims()
        assertEquals(0, secondPassCount)
        assertEquals(8, DealRepository.findById(deal.id)?.stock_available)
    }
}
