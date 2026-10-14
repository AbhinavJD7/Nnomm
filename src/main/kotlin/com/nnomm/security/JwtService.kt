package com.nnomm.security

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.nnomm.domain.ApiException
import com.nnomm.domain.AuthUser
import com.nnomm.domain.ErrorCode
import com.nnomm.domain.UserRole
import java.util.Date
import java.util.UUID

object JwtService {

    fun verifyToken(token: String, secret: String): AuthUser {
        try {
            val algorithm = Algorithm.HMAC256(secret)
            val verifier = JWT.require(algorithm).build()
            val decoded = verifier.verify(token)

            // Strict check: Algorithm must be explicitly HS256 (rejects 'none', RS256, etc.)
            if (!decoded.algorithm.equals("HS256", ignoreCase = true)) {
                throw ApiException(ErrorCode.UNAUTHENTICATED, "Algorithm must be HS256")
            }

            val sub = decoded.subject
                ?: throw ApiException(ErrorCode.UNAUTHENTICATED, "Missing 'sub' claim")
            val userId = try {
                UUID.fromString(sub)
            } catch (e: Exception) {
                throw ApiException(ErrorCode.UNAUTHENTICATED, "Invalid user UUID in 'sub'")
            }

            val roleStr = decoded.getClaim("role")?.asString()
                ?: throw ApiException(ErrorCode.UNAUTHENTICATED, "Missing 'role' claim")
            val role = UserRole.fromString(roleStr)
                ?: throw ApiException(ErrorCode.UNAUTHENTICATED, "Invalid 'role' claim")

            val restaurantIdStr = decoded.getClaim("restaurant_id")?.asString()
            val restaurantId = if (!restaurantIdStr.isNullOrBlank()) {
                try {
                    UUID.fromString(restaurantIdStr)
                } catch (e: Exception) {
                    throw ApiException(ErrorCode.UNAUTHENTICATED, "Invalid 'restaurant_id' UUID")
                }
            } else null

            // If restaurant_admin, restaurant_id must be provided
            if (role == UserRole.RESTAURANT_ADMIN && restaurantId == null) {
                throw ApiException(ErrorCode.UNAUTHENTICATED, "Restaurant admin must have 'restaurant_id'")
            }

            return AuthUser(userId = userId, role = role, restaurantId = restaurantId)
        } catch (e: ApiException) {
            throw e
        } catch (e: JWTVerificationException) {
            throw ApiException(ErrorCode.UNAUTHENTICATED, "Token verification failed: ${e.message}")
        } catch (e: Exception) {
            throw ApiException(ErrorCode.UNAUTHENTICATED, "Invalid token: ${e.message}")
        }
    }

    /**
     * Helper to mint valid HS256 tokens for automated tests and evaluation.
     */
    fun generateToken(
        user: AuthUser,
        secret: String,
        expiresInSeconds: Long = 3600
    ): String {
        val algorithm = Algorithm.HMAC256(secret)
        val now = System.currentTimeMillis()
        val builder = JWT.create()
            .withSubject(user.userId.toString())
            .withClaim("role", user.role.value)
            .withIssuedAt(Date(now))
            .withExpiresAt(Date(now + (expiresInSeconds * 1000)))

        if (user.restaurantId != null) {
            builder.withClaim("restaurant_id", user.restaurantId.toString())
        }

        return builder.sign(algorithm)
    }
}
