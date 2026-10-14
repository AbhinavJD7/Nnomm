package com.nnomm.config

object AppConfig {
    val port: Int = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val databaseUrl: String = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/nnomm_deals"
    val databaseUser: String = System.getenv("DATABASE_USER") ?: "nnomm_user"
    val databasePassword: String = System.getenv("DATABASE_PASSWORD") ?: "nnomm_pass"
    val jwtSecret: String = System.getenv("JWT_SECRET") ?: "secret123"
    val webhookSecret: String = System.getenv("WEBHOOK_SECRET") ?: "webhook123"
    val claimTtlSeconds: Long = System.getenv("CLAIM_TTL_SECONDS")?.toLongOrNull() ?: 600L
}
