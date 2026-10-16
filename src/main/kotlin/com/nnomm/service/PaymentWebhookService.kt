package com.nnomm.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.nnomm.config.AppConfig
import com.nnomm.domain.ApiException
import com.nnomm.domain.ErrorCode
import com.nnomm.domain.WebhookEvent
import com.nnomm.repository.WebhookRepository
import com.nnomm.security.HmacService

object PaymentWebhookService {

    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()

    suspend fun handleWebhook(signatureHeader: String?, rawBodyBytes: ByteArray) {
        // 1. Enforce HMAC-SHA256 signature and 300s clock skew check
        HmacService.verifyWebhookSignature(signatureHeader, rawBodyBytes, AppConfig.webhookSecret)

        // 2. Deserialize raw event bytes
        val rawJson = String(rawBodyBytes, Charsets.UTF_8)
        val event = try {
            objectMapper.readValue(rawJson, WebhookEvent::class.java)
        } catch (e: Exception) {
            throw ApiException(ErrorCode.VALIDATION_FAILED, "Malformed webhook event payload")
        }

        // 3. Atomically process event with row-level locking & deduplication
        WebhookRepository.processPaymentEvent(event, rawJson)
    }
}
