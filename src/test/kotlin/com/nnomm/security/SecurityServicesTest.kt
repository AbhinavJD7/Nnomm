package com.nnomm.security

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.nnomm.domain.ApiException
import com.nnomm.domain.AuthUser
import com.nnomm.domain.ErrorCode
import com.nnomm.domain.UserRole
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Date
import java.util.UUID

class SecurityServicesTest {

    private val secret = "test_jwt_secret_key_12345"
    private val webhookSecret = "test_webhook_secret_key_67890"

    @Test
    fun `valid customer token is successfully decoded`() {
        val userId = UUID.randomUUID()
        val user = AuthUser(userId = userId, role = UserRole.CUSTOMER)
        val token = JwtService.generateToken(user, secret)

        val decoded = JwtService.verifyToken(token, secret)

        assertEquals(userId, decoded.userId)
        assertEquals(UserRole.CUSTOMER, decoded.role)
        assertNull(decoded.restaurantId)
    }

    @Test
    fun `valid restaurant admin token contains restaurant_id`() {
        val adminId = UUID.randomUUID()
        val restId = UUID.randomUUID()
        val admin = AuthUser(userId = adminId, role = UserRole.RESTAURANT_ADMIN, restaurantId = restId)
        val token = JwtService.generateToken(admin, secret)

        val decoded = JwtService.verifyToken(token, secret)

        assertEquals(adminId, decoded.userId)
        assertEquals(UserRole.RESTAURANT_ADMIN, decoded.role)
        assertEquals(restId, decoded.restaurantId)
    }

    @Test
    fun `expired token is rejected with 401 UNAUTHENTICATED`() {
        val user = AuthUser(userId = UUID.randomUUID(), role = UserRole.CUSTOMER)
        val token = JwtService.generateToken(user, secret, expiresInSeconds = -10)

        val ex = assertThrows(ApiException::class.java) {
            JwtService.verifyToken(token, secret)
        }
        assertEquals(ErrorCode.UNAUTHENTICATED, ex.errorCode)
    }

    @Test
    fun `tampered token is rejected with 401 UNAUTHENTICATED`() {
        val user = AuthUser(userId = UUID.randomUUID(), role = UserRole.CUSTOMER)
        val token = JwtService.generateToken(user, secret)
        val tamperedToken = token + "xyz"

        val ex = assertThrows(ApiException::class.java) {
            JwtService.verifyToken(tamperedToken, secret)
        }
        assertEquals(ErrorCode.UNAUTHENTICATED, ex.errorCode)
    }

    @Test
    fun `algorithm none token is rejected with 401 UNAUTHENTICATED`() {
        val unsignedToken = JWT.create()
            .withSubject(UUID.randomUUID().toString())
            .withClaim("role", "customer")
            .withExpiresAt(Date(System.currentTimeMillis() + 3600000))
            .sign(Algorithm.none())

        val ex = assertThrows(ApiException::class.java) {
            JwtService.verifyToken(unsignedToken, secret)
        }
        assertEquals(ErrorCode.UNAUTHENTICATED, ex.errorCode)
    }

    @Test
    fun `valid webhook HMAC signature is accepted`() {
        val payload = """{"event_id":"evt_1","type":"payment.succeeded"}""".toByteArray(Charsets.UTF_8)
        val header = HmacService.generateSignatureHeader(payload, webhookSecret)

        assertDoesNotThrow {
            HmacService.verifyWebhookSignature(header, payload, webhookSecret)
        }
    }

    @Test
    fun `webhook with timestamp drift over 300s is rejected`() {
        val payload = """{"event_id":"evt_2"}""".toByteArray(Charsets.UTF_8)
        val staleTimestamp = Instant.now().epochSecond - 350 // 350s old
        val header = HmacService.generateSignatureHeader(payload, webhookSecret, timestamp = staleTimestamp)

        val ex = assertThrows(ApiException::class.java) {
            HmacService.verifyWebhookSignature(header, payload, webhookSecret)
        }
        assertEquals(ErrorCode.UNAUTHENTICATED, ex.errorCode)
    }

    @Test
    fun `webhook with tampered payload is rejected`() {
        val originalPayload = """{"amount_paise": 1000}""".toByteArray(Charsets.UTF_8)
        val header = HmacService.generateSignatureHeader(originalPayload, webhookSecret)

        val tamperedPayload = """{"amount_paise": 9999}""".toByteArray(Charsets.UTF_8)

        val ex = assertThrows(ApiException::class.java) {
            HmacService.verifyWebhookSignature(header, tamperedPayload, webhookSecret)
        }
        assertEquals(ErrorCode.UNAUTHENTICATED, ex.errorCode)
    }
}
