package com.nnomm.claims

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.nnomm.config.AppConfig
import com.nnomm.config.DatabaseFactory
import com.nnomm.domain.*
import com.nnomm.module
import com.nnomm.repository.DealRepository
import com.nnomm.security.JwtService
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
class ClaimRoutesTest {

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
    private val secret = AppConfig.jwtSecret

    private fun createCustomerToken(customerId: UUID = UUID.randomUUID()): Pair<UUID, String> {
        val user = AuthUser(userId = customerId, role = UserRole.CUSTOMER)
        return customerId to JwtService.generateToken(user, secret)
    }

    private fun createAdminToken(restaurantId: UUID = UUID.randomUUID()): Pair<UUID, String> {
        val user = AuthUser(userId = UUID.randomUUID(), role = UserRole.RESTAURANT_ADMIN, restaurantId = restaurantId)
        return restaurantId to JwtService.generateToken(user, secret)
    }

    private fun createLiveDeal(stock: Int = 10, pricePaise: Int = 9900): DealResponse = runBlocking {
        val restId = UUID.randomUUID()
        DealRepository.ensureRestaurantExists(restId)
        DealRepository.createDeal(
            restaurantId = restId,
            title = "Test Flash Deal",
            pricePaise = pricePaise,
            stockTotal = stock,
            startsAt = Instant.now().minusSeconds(100),
            endsAt = Instant.now().plusSeconds(3600)
        )
    }

    @Test
    fun `customer can claim deal and replay idempotently`() = testApplication {
        application { module(initDatabase = false) }

        val deal = createLiveDeal(stock = 10, pricePaise = 9900)
        val (_, token) = createCustomerToken()
        val idempotencyKey = "key_" + UUID.randomUUID().toString()

        val reqBody = objectMapper.writeValueAsString(ClaimRequest(quantity = 2))

        // 1. Initial claim request
        val res1 = client.post("/v1/deals/${deal.id}/claims") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header("Idempotency-Key", idempotencyKey)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(reqBody)
        }

        assertEquals(HttpStatusCode.Created, res1.status)
        val claim1 = objectMapper.readValue(res1.bodyAsText(), ClaimResponse::class.java)
        assertEquals(deal.id, claim1.deal_id)
        assertEquals(2, claim1.quantity)
        assertEquals(19800, claim1.amount_paise)
        assertEquals(ClaimStatus.RESERVED, claim1.status)

        // 2. Replay with identical key: must return same body and Idempotent-Replayed: true
        val res2 = client.post("/v1/deals/${deal.id}/claims") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header("Idempotency-Key", idempotencyKey)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(reqBody)
        }

        assertEquals(HttpStatusCode.Created, res2.status)
        assertEquals("true", res2.headers["Idempotent-Replayed"])
        val claim2 = objectMapper.readValue(res2.bodyAsText(), ClaimResponse::class.java)
        assertEquals(claim1.id, claim2.id)

        // Verify stock only decremented once (10 - 2 = 8)
        val updatedDeal = DealRepository.findById(deal.id)
        assertEquals(8, updatedDeal?.stock_available)
    }

    @Test
    fun `reusing key with different quantity returns 422 IDEMPOTENCY_KEY_REUSED`() = testApplication {
        application { module(initDatabase = false) }

        val deal = createLiveDeal(stock = 10)
        val (_, token) = createCustomerToken()
        val key = "reused_key_" + UUID.randomUUID().toString()

        // First call with quantity = 1
        client.post("/v1/deals/${deal.id}/claims") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header("Idempotency-Key", key)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(objectMapper.writeValueAsString(ClaimRequest(quantity = 1)))
        }

        // Second call with same key but quantity = 2
        val res = client.post("/v1/deals/${deal.id}/claims") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header("Idempotency-Key", key)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(objectMapper.writeValueAsString(ClaimRequest(quantity = 2)))
        }

        assertEquals(HttpStatusCode.UnprocessableEntity, res.status)
        val err = objectMapper.readValue(res.bodyAsText(), ErrorResponse::class.java)
        assertEquals("IDEMPOTENCY_KEY_REUSED", err.error.code)
    }

    @Test
    fun `missing or malformed idempotency key returns 400 IDEMPOTENCY_KEY_INVALID`() = testApplication {
        application { module(initDatabase = false) }

        val deal = createLiveDeal()
        val (_, token) = createCustomerToken()

        // Missing key
        val resMissing = client.post("/v1/deals/${deal.id}/claims") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(objectMapper.writeValueAsString(ClaimRequest(quantity = 1)))
        }
        assertEquals(HttpStatusCode.BadRequest, resMissing.status)
        val errMissing = objectMapper.readValue(resMissing.bodyAsText(), ErrorResponse::class.java)
        assertEquals("IDEMPOTENCY_KEY_INVALID", errMissing.error.code)

        // Malformed key (contains invalid character '@')
        val resMalformed = client.post("/v1/deals/${deal.id}/claims") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header("Idempotency-Key", "invalid@key!")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(objectMapper.writeValueAsString(ClaimRequest(quantity = 1)))
        }
        assertEquals(HttpStatusCode.BadRequest, resMalformed.status)
        val errMalformed = objectMapper.readValue(resMalformed.bodyAsText(), ErrorResponse::class.java)
        assertEquals("IDEMPOTENCY_KEY_INVALID", errMalformed.error.code)
    }

    @Test
    fun `customer cannot hold two active claims on same deal (409 ALREADY_CLAIMED)`() = testApplication {
        application { module(initDatabase = false) }

        val deal = createLiveDeal(stock = 10)
        val (_, token) = createCustomerToken()

        // First claim
        client.post("/v1/deals/${deal.id}/claims") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header("Idempotency-Key", "key1_" + UUID.randomUUID())
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(objectMapper.writeValueAsString(ClaimRequest(quantity = 1)))
        }

        // Second claim with different key on same deal
        val res = client.post("/v1/deals/${deal.id}/claims") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header("Idempotency-Key", "key2_" + UUID.randomUUID())
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(objectMapper.writeValueAsString(ClaimRequest(quantity = 1)))
        }

        assertEquals(HttpStatusCode.Conflict, res.status)
        val err = objectMapper.readValue(res.bodyAsText(), ErrorResponse::class.java)
        assertEquals("ALREADY_CLAIMED", err.error.code)
    }

    @Test
    fun `sold out deal returns 409 SOLD_OUT and replays sold out`() = testApplication {
        application { module(initDatabase = false) }

        val deal = createLiveDeal(stock = 1)
        val (_, cust1Token) = createCustomerToken()
        val (_, cust2Token) = createCustomerToken()

        // Customer 1 takes the 1 unit
        client.post("/v1/deals/${deal.id}/claims") {
            header(HttpHeaders.Authorization, "Bearer $cust1Token")
            header("Idempotency-Key", "take_last_" + UUID.randomUUID())
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(objectMapper.writeValueAsString(ClaimRequest(quantity = 1)))
        }

        // Customer 2 tries to claim 1 unit -> SOLD_OUT
        val cust2Key = "cust2_key_" + UUID.randomUUID()
        val res = client.post("/v1/deals/${deal.id}/claims") {
            header(HttpHeaders.Authorization, "Bearer $cust2Token")
            header("Idempotency-Key", cust2Key)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(objectMapper.writeValueAsString(ClaimRequest(quantity = 1)))
        }

        assertEquals(HttpStatusCode.Conflict, res.status)
        val err = objectMapper.readValue(res.bodyAsText(), ErrorResponse::class.java)
        assertEquals("SOLD_OUT", err.error.code)

        // Customer 2 replays with same key -> remains SOLD_OUT with Idempotent-Replayed: true
        val replayRes = client.post("/v1/deals/${deal.id}/claims") {
            header(HttpHeaders.Authorization, "Bearer $cust2Token")
            header("Idempotency-Key", cust2Key)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(objectMapper.writeValueAsString(ClaimRequest(quantity = 1)))
        }

        assertEquals(HttpStatusCode.Conflict, replayRes.status)
        assertEquals("true", replayRes.headers["Idempotent-Replayed"])
    }

    @Test
    fun `claim visibility is strictly enforced with 404 for unauthorized users`() = testApplication {
        application { module(initDatabase = false) }

        val deal = createLiveDeal(stock = 5)
        val (_, cust1Token) = createCustomerToken()
        val (_, cust2Token) = createCustomerToken()

        val createRes = client.post("/v1/deals/${deal.id}/claims") {
            header(HttpHeaders.Authorization, "Bearer $cust1Token")
            header("Idempotency-Key", "key_" + UUID.randomUUID())
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(objectMapper.writeValueAsString(ClaimRequest(quantity = 1)))
        }
        val claim = objectMapper.readValue(createRes.bodyAsText(), ClaimResponse::class.java)

        // 1. Owner customer can read their claim
        val ownerRes = client.get("/v1/claims/${claim.id}") {
            header(HttpHeaders.Authorization, "Bearer $cust1Token")
        }
        assertEquals(HttpStatusCode.OK, ownerRes.status)

        // 2. Different customer gets 404 NOT_FOUND (NOT 403!)
        val otherCustRes = client.get("/v1/claims/${claim.id}") {
            header(HttpHeaders.Authorization, "Bearer $cust2Token")
        }
        assertEquals(HttpStatusCode.NotFound, otherCustRes.status)

        // 3. Admin of that restaurant can read the claim
        val (_, adminDealToken) = createAdminToken(deal.restaurant_id)
        val dealAdminRes = client.get("/v1/claims/${claim.id}") {
            header(HttpHeaders.Authorization, "Bearer $adminDealToken")
        }
        assertEquals(HttpStatusCode.OK, dealAdminRes.status)

        // 4. Admin of a different restaurant gets 404 NOT_FOUND (NOT 403!)
        val (_, otherAdminToken) = createAdminToken(UUID.randomUUID())
        val otherAdminRes = client.get("/v1/claims/${claim.id}") {
            header(HttpHeaders.Authorization, "Bearer $otherAdminToken")
        }
        assertEquals(HttpStatusCode.NotFound, otherAdminRes.status)
    }
}
