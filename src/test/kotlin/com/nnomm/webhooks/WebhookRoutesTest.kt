package com.nnomm.webhooks

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.nnomm.config.AppConfig
import com.nnomm.config.DatabaseFactory
import com.nnomm.domain.*
import com.nnomm.module
import com.nnomm.repository.ClaimRepository
import com.nnomm.repository.DealRepository
import com.nnomm.security.HmacService
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID

@Testcontainers
class WebhookRoutesTest {

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

    private fun createTestClaim(pricePaise: Int = 10000, quantity: Int = 2, ttlSeconds: Long = 600L): ClaimResponse = runBlocking {
        val restId = UUID.randomUUID()
        DealRepository.ensureRestaurantExists(restId)
        val deal = DealRepository.createDeal(
            restaurantId = restId,
            title = "Webhook Test Deal",
            pricePaise = pricePaise,
            stockTotal = 20,
            startsAt = Instant.now().minusSeconds(100),
            endsAt = Instant.now().plusSeconds(3600)
        )
        ClaimRepository.executeClaimTransaction(
            dealId = deal.id,
            customerId = UUID.randomUUID(),
            quantity = quantity,
            claimTtlSeconds = ttlSeconds
        )
    }

    private fun sendWebhook(client: io.ktor.client.HttpClient, event: WebhookEvent, secret: String = webhookSecret) = runBlocking {
        val payload = objectMapper.writeValueAsBytes(event)
        val signatureHeader = HmacService.generateSignatureHeader(payload, secret)

        client.post("/v1/webhooks/payments") {
            header("X-Payment-Signature", signatureHeader)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(payload)
        }
    }

    @Test
    fun `matching payment marks claim as PAID`() = testApplication {
        application { module(initDatabase = false) }

        val claim = createTestClaim(pricePaise = 10000, quantity = 2) // amount = 20000
        val event = WebhookEvent(
            event_id = "evt_" + UUID.randomUUID(),
            type = "payment.succeeded",
            claim_id = claim.id,
            amount_paise = 20000,
            occurred_at = Instant.now().toString()
        )

        val res = sendWebhook(client, event)
        assertEquals(HttpStatusCode.OK, res.status)

        val updated = ClaimRepository.findClaimWithDealInfo(claim.id)?.first
        assertEquals(ClaimStatus.PAID, updated?.status)
    }

    @Test
    fun `mismatched payment amount marks claim as NEEDS_REVIEW`() = testApplication {
        application { module(initDatabase = false) }

        val claim = createTestClaim(pricePaise = 10000, quantity = 2) // amount = 20000
        val event = WebhookEvent(
            event_id = "evt_" + UUID.randomUUID(),
            type = "payment.succeeded",
            claim_id = claim.id,
            amount_paise = 15000, // differs!
            occurred_at = Instant.now().toString()
        )

        val res = sendWebhook(client, event)
        assertEquals(HttpStatusCode.OK, res.status)

        val updated = ClaimRepository.findClaimWithDealInfo(claim.id)?.first
        assertEquals(ClaimStatus.NEEDS_REVIEW, updated?.status)
    }

    @Test
    fun `late payment on expired claim marks claim as REFUND_PENDING`() = testApplication {
        application { module(initDatabase = false) }

        // Create claim with negative TTL so it is already expired
        val claim = createTestClaim(pricePaise = 10000, quantity = 1, ttlSeconds = -10L)
        val event = WebhookEvent(
            event_id = "evt_" + UUID.randomUUID(),
            type = "payment.succeeded",
            claim_id = claim.id,
            amount_paise = 10000,
            occurred_at = Instant.now().toString()
        )

        val res = sendWebhook(client, event)
        assertEquals(HttpStatusCode.OK, res.status)

        val updated = ClaimRepository.findClaimWithDealInfo(claim.id)?.first
        assertEquals(ClaimStatus.REFUND_PENDING, updated?.status)
    }

    @Test
    fun `payment failed leaves claim in RESERVED`() = testApplication {
        application { module(initDatabase = false) }

        val claim = createTestClaim(pricePaise = 5000, quantity = 1)
        val event = WebhookEvent(
            event_id = "evt_" + UUID.randomUUID(),
            type = "payment.failed",
            claim_id = claim.id,
            amount_paise = 5000,
            occurred_at = Instant.now().toString()
        )

        val res = sendWebhook(client, event)
        assertEquals(HttpStatusCode.OK, res.status)

        val updated = ClaimRepository.findClaimWithDealInfo(claim.id)?.first
        assertEquals(ClaimStatus.RESERVED, updated?.status)
    }

    @Test
    fun `duplicate event_id returns 200 no-op without double processing`() = testApplication {
        application { module(initDatabase = false) }

        val claim = createTestClaim(pricePaise = 8000, quantity = 1)
        val eventId = "dup_evt_" + UUID.randomUUID()
        val event = WebhookEvent(
            event_id = eventId,
            type = "payment.succeeded",
            claim_id = claim.id,
            amount_paise = 8000,
            occurred_at = Instant.now().toString()
        )

        val res1 = sendWebhook(client, event)
        assertEquals(HttpStatusCode.OK, res1.status)

        // Resend identical event
        val res2 = sendWebhook(client, event)
        assertEquals(HttpStatusCode.OK, res2.status)

        val updated = ClaimRepository.findClaimWithDealInfo(claim.id)?.first
        assertEquals(ClaimStatus.PAID, updated?.status)
    }

    @Test
    fun `unknown claim_id returns 200 to satisfy provider retry loop`() = testApplication {
        application { module(initDatabase = false) }

        val event = WebhookEvent(
            event_id = "evt_unknown_" + UUID.randomUUID(),
            type = "payment.succeeded",
            claim_id = UUID.randomUUID(), // non-existent
            amount_paise = 5000,
            occurred_at = Instant.now().toString()
        )

        val res = sendWebhook(client, event)
        assertEquals(HttpStatusCode.OK, res.status)
    }

    @Test
    fun `invalid signature returns 401 UNAUTHENTICATED`() = testApplication {
        application { module(initDatabase = false) }

        val payload = """{"event_id":"evt_bad","type":"payment.succeeded"}""".toByteArray(Charsets.UTF_8)
        val res = client.post("/v1/webhooks/payments") {
            header("X-Payment-Signature", "t=12345,v1=bad_signature_hex")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(payload)
        }

        assertEquals(HttpStatusCode.Unauthorized, res.status)
        val err = objectMapper.readValue(res.bodyAsText(), ErrorResponse::class.java)
        assertEquals("UNAUTHENTICATED", err.error.code)
    }
}
