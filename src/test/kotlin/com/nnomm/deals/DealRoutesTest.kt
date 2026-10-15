package com.nnomm.deals

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.nnomm.config.AppConfig
import com.nnomm.config.DatabaseFactory
import com.nnomm.domain.AuthUser
import com.nnomm.domain.CreateDealRequest
import com.nnomm.domain.DealResponse
import com.nnomm.domain.ErrorResponse
import com.nnomm.domain.UserRole
import com.nnomm.module
import com.nnomm.security.JwtService
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
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
class DealRoutesTest {

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

    private fun createAdminToken(restaurantId: UUID = UUID.randomUUID()): String {
        val admin = AuthUser(userId = UUID.randomUUID(), role = UserRole.RESTAURANT_ADMIN, restaurantId = restaurantId)
        return JwtService.generateToken(admin, secret)
    }

    private fun createCustomerToken(): String {
        val customer = AuthUser(userId = UUID.randomUUID(), role = UserRole.CUSTOMER)
        return JwtService.generateToken(customer, secret)
    }

    @Test
    fun `restaurant admin can create deal and fetch it`() = testApplication {
        application { module(initDatabase = false) }

        val restId = UUID.randomUUID()
        val token = createAdminToken(restId)

        val createReq = CreateDealRequest(
            title = "Hyderabadi Biryani",
            price_paise = 25000,
            stock_total = 20,
            starts_at = Instant.now().minusSeconds(60).toString(),
            ends_at = Instant.now().plusSeconds(3600).toString()
        )

        val createRes = client.post("/v1/deals") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(objectMapper.writeValueAsString(createReq))
        }

        assertEquals(HttpStatusCode.Created, createRes.status)
        val createdDeal = objectMapper.readValue(createRes.bodyAsText(), DealResponse::class.java)
        assertEquals("Hyderabadi Biryani", createdDeal.title)
        assertEquals(25000, createdDeal.price_paise)
        assertEquals(20, createdDeal.stock_total)
        assertEquals(20, createdDeal.stock_available)
        assertEquals(restId, createdDeal.restaurant_id)

        // Customer fetching deal
        val custToken = createCustomerToken()
        val getRes = client.get("/v1/deals/${createdDeal.id}") {
            header(HttpHeaders.Authorization, "Bearer $custToken")
        }
        assertEquals(HttpStatusCode.OK, getRes.status)
        val fetchedDeal = objectMapper.readValue(getRes.bodyAsText(), DealResponse::class.java)
        assertEquals(createdDeal.id, fetchedDeal.id)
    }

    @Test
    fun `customer cannot create deal and receives 403 FORBIDDEN`() = testApplication {
        application { module(initDatabase = false) }

        val custToken = createCustomerToken()
        val createReq = CreateDealRequest(
            title = "Burger",
            price_paise = 5000,
            stock_total = 10,
            starts_at = Instant.now().toString(),
            ends_at = Instant.now().plusSeconds(3600).toString()
        )

        val res = client.post("/v1/deals") {
            header(HttpHeaders.Authorization, "Bearer $custToken")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(objectMapper.writeValueAsString(createReq))
        }

        assertEquals(HttpStatusCode.Forbidden, res.status)
        val err = objectMapper.readValue(res.bodyAsText(), ErrorResponse::class.java)
        assertEquals("FORBIDDEN", err.error.code)
    }

    @Test
    fun `starts_at after ends_at receives 400 VALIDATION_FAILED`() = testApplication {
        application { module(initDatabase = false) }

        val token = createAdminToken()
        val createReq = CreateDealRequest(
            title = "Invalid Timing Deal",
            price_paise = 1000,
            stock_total = 5,
            starts_at = Instant.now().plusSeconds(3600).toString(),
            ends_at = Instant.now().toString() // ends before starts!
        )

        val res = client.post("/v1/deals") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(objectMapper.writeValueAsString(createReq))
        }

        assertEquals(HttpStatusCode.BadRequest, res.status)
        val err = objectMapper.readValue(res.bodyAsText(), ErrorResponse::class.java)
        assertEquals("VALIDATION_FAILED", err.error.code)
    }

    @Test
    fun `non existent deal returns 404 NOT_FOUND`() = testApplication {
        application { module(initDatabase = false) }

        val token = createCustomerToken()
        val res = client.get("/v1/deals/${UUID.randomUUID()}") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.NotFound, res.status)
        val err = objectMapper.readValue(res.bodyAsText(), ErrorResponse::class.java)
        assertEquals("NOT_FOUND", err.error.code)
    }
}
