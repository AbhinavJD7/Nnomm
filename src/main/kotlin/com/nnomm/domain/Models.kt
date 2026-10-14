package com.nnomm.domain

import com.fasterxml.jackson.annotation.JsonFormat
import java.time.Instant
import java.util.UUID

// --- Deals ---

data class CreateDealRequest(
    val title: String?,
    val price_paise: Int?,
    val stock_total: Int?,
    val starts_at: String?,
    val ends_at: String?
)

data class DealResponse(
    val id: UUID,
    val restaurant_id: UUID,
    val title: String,
    val price_paise: Int,
    val stock_total: Int,
    val stock_available: Int,
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
    val starts_at: Instant,
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
    val ends_at: Instant
)

// --- Claims ---

data class ClaimRequest(
    val quantity: Int?
)

data class ClaimResponse(
    val id: UUID,
    val deal_id: UUID,
    val customer_id: UUID,
    val quantity: Int,
    val amount_paise: Int,
    val status: ClaimStatus,
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
    val expires_at: Instant
)

// --- Webhooks ---

data class WebhookEvent(
    val event_id: String,
    val type: String,
    val claim_id: UUID?,
    val amount_paise: Int?,
    val occurred_at: String?
)

// --- Authentication & Security Context ---

data class AuthUser(
    val userId: UUID,
    val role: UserRole,
    val restaurantId: UUID? = null
)

// --- Idempotency Internal Record ---

data class IdempotencyRecord(
    val customerId: UUID,
    val idempotencyKey: String,
    val dealId: UUID,
    val quantity: Int,
    val status: String,
    val responseStatusCode: Int?,
    val responseBody: String?,
    val createdAt: Instant,
    val updatedAt: Instant
)
