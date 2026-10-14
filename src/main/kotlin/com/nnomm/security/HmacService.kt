package com.nnomm.security

import com.nnomm.domain.ApiException
import com.nnomm.domain.ErrorCode
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

object HmacService {
    private const val MAX_CLOCK_SKEW_SECONDS = 300L

    fun verifyWebhookSignature(header: String?, rawBodyBytes: ByteArray, secret: String) {
        if (header.isNullOrBlank()) {
            throw ApiException(ErrorCode.UNAUTHENTICATED, "Missing X-Payment-Signature header")
        }

        // Expected format: t=<unix seconds>,v1=<hex HMAC-SHA256>
        val parts = header.split(",").associate {
            val keyValue = it.split("=", limit = 2)
            if (keyValue.size == 2) keyValue[0].trim() to keyValue[1].trim() else "" to ""
        }

        val timestampStr = parts["t"]
            ?: throw ApiException(ErrorCode.UNAUTHENTICATED, "Missing timestamp in signature header")
        val receivedSignature = parts["v1"]
            ?: throw ApiException(ErrorCode.UNAUTHENTICATED, "Missing v1 in signature header")

        val timestamp = timestampStr.toLongOrNull()
            ?: throw ApiException(ErrorCode.UNAUTHENTICATED, "Invalid timestamp format in signature header")

        // 1. Clock skew check: reject if > 300s away from current time
        val now = Instant.now().epochSecond
        if (abs(now - timestamp) > MAX_CLOCK_SKEW_SECONDS) {
            throw ApiException(ErrorCode.UNAUTHENTICATED, "Webhook timestamp drift exceeds 300 seconds")
        }

        // 2. Message is: ${t}.${rawBodyBytes}
        val prefix = "$timestamp.".toByteArray(Charsets.UTF_8)
        val payloadToSign = prefix + rawBodyBytes

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val expectedBytes = mac.doFinal(payloadToSign)
        val expectedHex = expectedBytes.joinToString("") { "%02x".format(it) }

        // 3. Constant-time comparison to prevent timing attacks
        val receivedBytes = receivedSignature.lowercase().toByteArray(Charsets.UTF_8)
        val computedBytes = expectedHex.lowercase().toByteArray(Charsets.UTF_8)

        if (!MessageDigest.isEqual(receivedBytes, computedBytes)) {
            throw ApiException(ErrorCode.UNAUTHENTICATED, "Invalid HMAC signature")
        }
    }

    /**
     * Helper to compute valid signatures for test suites.
     */
    fun generateSignatureHeader(
        rawBodyBytes: ByteArray,
        secret: String,
        timestamp: Long = Instant.now().epochSecond
    ): String {
        val prefix = "$timestamp.".toByteArray(Charsets.UTF_8)
        val payloadToSign = prefix + rawBodyBytes

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signatureHex = mac.doFinal(payloadToSign).joinToString("") { "%02x".format(it) }

        return "t=$timestamp,v1=$signatureHex"
    }
}
