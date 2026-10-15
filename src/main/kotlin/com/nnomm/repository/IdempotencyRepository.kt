package com.nnomm.repository

import com.nnomm.config.DatabaseFactory
import com.nnomm.domain.ApiException
import com.nnomm.domain.ErrorCode
import java.sql.Connection
import java.time.Instant
import java.util.UUID

object IdempotencyRepository {

    sealed class KeyAcquisitionResult {
        object Acquired : KeyAcquisitionResult()
        data class Replay(val statusCode: Int, val body: String) : KeyAcquisitionResult()
    }

    suspend fun acquireOrReplay(
        customerId: UUID,
        key: String,
        dealId: UUID,
        quantity: Int
    ): KeyAcquisitionResult = DatabaseFactory.query { conn ->
        acquireOrReplayInTransaction(conn, customerId, key, dealId, quantity)
    }

    fun acquireOrReplayInTransaction(
        conn: Connection,
        customerId: UUID,
        key: String,
        dealId: UUID,
        quantity: Int
    ): KeyAcquisitionResult {
        val selectSql = """
            SELECT deal_id, quantity, status, response_status_code, response_body, created_at
            FROM idempotency_records
            WHERE customer_id = ? AND idempotency_key = ?
            FOR UPDATE;
        """.trimIndent()

        conn.prepareStatement(selectSql).use { stmt ->
            stmt.setObject(1, customerId)
            stmt.setString(2, key)
            val rs = stmt.executeQuery()

            if (rs.next()) {
                val existingDealId = rs.getObject("deal_id", UUID::class.java)
                val existingQuantity = rs.getInt("quantity")
                val status = rs.getString("status")
                val statusCode = rs.getInt("response_status_code")
                val body = rs.getString("response_body")
                val createdAt = rs.getTimestamp("created_at").toInstant()

                // Rule: Same key, different deal or quantity -> 422 IDEMPOTENCY_KEY_REUSED
                if (existingDealId != dealId || existingQuantity != quantity) {
                    throw ApiException(
                        ErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "Idempotency key was previously used with a different deal or quantity"
                    )
                }

                // Rule: If already COMPLETED within 24h -> replay stored response
                if (status == "COMPLETED" && body != null) {
                    return KeyAcquisitionResult.Replay(statusCode, body)
                }

                // Rule: If IN_PROGRESS and <= 60 seconds -> 409 REQUEST_IN_PROGRESS
                val ageSeconds = Instant.now().epochSecond - createdAt.epochSecond
                if (ageSeconds <= 60) {
                    throw ApiException(
                        ErrorCode.REQUEST_IN_PROGRESS,
                        "A request with the same key is currently in progress"
                    )
                }

                // Rule: Crashes: worker died mid-request (> 60 seconds ago), allow takeover
                val updateSql = """
                    UPDATE idempotency_records 
                    SET created_at = NOW(), updated_at = NOW(), status = 'IN_PROGRESS'
                    WHERE customer_id = ? AND idempotency_key = ?;
                """.trimIndent()
                conn.prepareStatement(updateSql).use { updateStmt ->
                    updateStmt.setObject(1, customerId)
                    updateStmt.setString(2, key)
                    updateStmt.executeUpdate()
                }
                return KeyAcquisitionResult.Acquired
            }
        }

        // Key doesn't exist: atomically insert as IN_PROGRESS
        val insertSql = """
            INSERT INTO idempotency_records (customer_id, idempotency_key, deal_id, quantity, status, created_at, updated_at)
            VALUES (?, ?, ?, ?, 'IN_PROGRESS', NOW(), NOW());
        """.trimIndent()
        conn.prepareStatement(insertSql).use { stmt ->
            stmt.setObject(1, customerId)
            stmt.setString(2, key)
            stmt.setObject(3, dealId)
            stmt.setInt(4, quantity)
            stmt.executeUpdate()
        }
        return KeyAcquisitionResult.Acquired
    }

    suspend fun saveOutcome(
        customerId: UUID,
        key: String,
        statusCode: Int,
        responseBody: String
    ) = DatabaseFactory.query { conn ->
        val sql = """
            UPDATE idempotency_records
            SET status = 'COMPLETED',
                response_status_code = ?,
                response_body = ?,
                updated_at = NOW()
            WHERE customer_id = ? AND idempotency_key = ?;
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, statusCode)
            stmt.setString(2, responseBody)
            stmt.setObject(3, customerId)
            stmt.setString(4, key)
            stmt.executeUpdate()
        }
    }
}
