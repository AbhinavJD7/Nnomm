package com.nnomm.domain

import io.ktor.http.*

enum class ErrorCode(val httpStatus: HttpStatusCode, val defaultMessage: String) {
    VALIDATION_FAILED(HttpStatusCode.BadRequest, "A field is missing, malformed or out of range"),
    IDEMPOTENCY_KEY_INVALID(HttpStatusCode.BadRequest, "Idempotency-Key is missing or malformed"),
    UNAUTHENTICATED(HttpStatusCode.Unauthorized, "Bad or expired token, or a bad webhook signature"),
    FORBIDDEN(HttpStatusCode.Forbidden, "The caller's role can't use this endpoint"),
    NOT_FOUND(HttpStatusCode.NotFound, "It doesn't exist, or the caller may not see it"),
    SOLD_OUT(HttpStatusCode.Conflict, "Not enough units left for this quantity"),
    ALREADY_CLAIMED(HttpStatusCode.Conflict, "The customer already holds an active claim on this deal"),
    DEAL_NOT_LIVE(HttpStatusCode.Conflict, "The deal hasn't started yet, or has ended"),
    REQUEST_IN_PROGRESS(HttpStatusCode.Conflict, "A request with the same key is still running"),
    PAYLOAD_TOO_LARGE(HttpStatusCode.PayloadTooLarge, "The body is over 16 KB"),
    IDEMPOTENCY_KEY_REUSED(HttpStatusCode.UnprocessableEntity, "Same key, different request"),
    INTERNAL(HttpStatusCode.InternalServerError, "Unexpected failures only, never the cases above")
}

data class ErrorDetails(
    val code: String,
    val message: String
)

data class ErrorResponse(
    val error: ErrorDetails
) {
    constructor(errorCode: ErrorCode, message: String? = null) : this(
        ErrorDetails(
            code = errorCode.name,
            message = message ?: errorCode.defaultMessage
        )
    )
}

open class ApiException(
    val errorCode: ErrorCode,
    customMessage: String? = null,
    cause: Throwable? = null
) : RuntimeException(customMessage ?: errorCode.defaultMessage, cause) {
    fun toErrorResponse(): ErrorResponse = ErrorResponse(errorCode, message)
}
