package com.nnomm.worker

import com.nnomm.config.DatabaseFactory
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.util.UUID

object ClaimExpiryWorker {

    private val logger = LoggerFactory.getLogger(ClaimExpiryWorker::class.java)

    fun start(scope: CoroutineScope, intervalMs: Long = 1000L): Job {
        return scope.launch(Dispatchers.IO) {
            logger.info("Starting ClaimExpiryWorker with interval ${intervalMs}ms")
            while (isActive) {
                try {
                    val expiredCount = expireOverdueClaims()
                    if (expiredCount > 0) {
                        logger.info("Expired $expiredCount overdue claims and restored stock")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("Error in ClaimExpiryWorker execution: ${e.message}", e)
                }
                delay(intervalMs)
            }
        }
    }

    suspend fun expireOverdueClaims(): Int = DatabaseFactory.query { conn ->
        // Use FOR UPDATE SKIP LOCKED so multi-instance workers never block each other
        val selectSql = """
            SELECT id, deal_id, quantity 
            FROM claims 
            WHERE status = 'RESERVED' AND expires_at <= NOW() 
            LIMIT 100 
            FOR UPDATE SKIP LOCKED;
        """.trimIndent()

        val expiredClaims = mutableListOf<Triple<UUID, UUID, Int>>()
        conn.prepareStatement(selectSql).use { stmt ->
            val rs = stmt.executeQuery()
            while (rs.next()) {
                val claimId = rs.getObject("id", UUID::class.java)
                val dealId = rs.getObject("deal_id", UUID::class.java)
                val quantity = rs.getInt("quantity")
                expiredClaims.add(Triple(claimId, dealId, quantity))
            }
        }

        if (expiredClaims.isEmpty()) {
            return@query 0
        }

        val updateClaimSql = "UPDATE claims SET status = 'EXPIRED', updated_at = NOW() WHERE id = ?;"
        val restockSql = "UPDATE deals SET stock_available = stock_available + ? WHERE id = ?;"

        conn.prepareStatement(updateClaimSql).use { claimStmt ->
            conn.prepareStatement(restockSql).use { dealStmt ->
                for ((claimId, dealId, quantity) in expiredClaims) {
                    // 1. Mark claim EXPIRED
                    claimStmt.setObject(1, claimId)
                    claimStmt.executeUpdate()

                    // 2. Atomically restock deal stock
                    dealStmt.setInt(1, quantity)
                    dealStmt.setObject(2, dealId)
                    dealStmt.executeUpdate()
                }
            }
        }

        expiredClaims.size
    }
}
