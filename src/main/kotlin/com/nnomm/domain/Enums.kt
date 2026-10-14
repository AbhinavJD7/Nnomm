package com.nnomm.domain

enum class ClaimStatus {
    RESERVED,
    PAID,
    NEEDS_REVIEW,
    EXPIRED,
    REFUND_PENDING,
    CANCELLED
}

enum class UserRole(val value: String) {
    CUSTOMER("customer"),
    RESTAURANT_ADMIN("restaurant_admin");

    companion object {
        fun fromString(value: String?): UserRole? =
            entries.firstOrNull { it.value.equals(value, ignoreCase = true) }
    }
}

enum class WebhookEventType(val value: String) {
    PAYMENT_SUCCEEDED("payment.succeeded"),
    PAYMENT_FAILED("payment.failed");

    companion object {
        fun fromString(value: String?): WebhookEventType? =
            entries.firstOrNull { it.value.equals(value, ignoreCase = true) }
    }
}
