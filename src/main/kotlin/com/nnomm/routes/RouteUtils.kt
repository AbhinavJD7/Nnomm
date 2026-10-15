package com.nnomm.routes

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.nnomm.config.AppConfig
import com.nnomm.domain.ApiException
import com.nnomm.domain.AuthUser
import com.nnomm.domain.ErrorCode
import com.nnomm.security.JwtService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*

const val MAX_BODY_BYTES = 16 * 1024 // 16 KB

suspend fun ApplicationCall.receiveRawBytesWithLimit(maxBytes: Int = MAX_BODY_BYTES): ByteArray {
    val contentLength = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (contentLength != null && contentLength > maxBytes) {
        throw ApiException(ErrorCode.PAYLOAD_TOO_LARGE)
    }

    val bytes = receive<ByteArray>()
    if (bytes.size > maxBytes) {
        throw ApiException(ErrorCode.PAYLOAD_TOO_LARGE)
    }
    return bytes
}

inline fun <reified T> ByteArray.parseJson(): T {
    return try {
        jacksonObjectMapper().readValue(this, T::class.java)
    } catch (e: Exception) {
        throw ApiException(ErrorCode.VALIDATION_FAILED, "Malformed JSON payload: ${e.message}")
    }
}

fun ApplicationCall.extractAuthUser(): AuthUser {
    val authHeader = request.headers[HttpHeaders.Authorization]
        ?: throw ApiException(ErrorCode.UNAUTHENTICATED, "Missing Authorization header")

    if (!authHeader.startsWith("Bearer ", ignoreCase = true)) {
        throw ApiException(ErrorCode.UNAUTHENTICATED, "Invalid Authorization header format")
    }

    val token = authHeader.substring(7).trim()
    return JwtService.verifyToken(token, AppConfig.jwtSecret)
}

suspend fun ApplicationCall.respondError(apiException: ApiException) {
    respond(apiException.errorCode.httpStatus, apiException.toErrorResponse())
}
