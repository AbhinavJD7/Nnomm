package com.nnomm.repository

import com.nnomm.config.DatabaseFactory
import com.nnomm.domain.ClaimResponse
import com.nnomm.domain.ClaimStatus
import com.nnomm.domain.WebhookEvent
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

object WebhookRepository {

    suspend fun isEventProcessed(eventId: String): Boolean = DatabaseFactory.query { conn ->
        val sql = "SELECT event_id FROM processed_webhooks WHERE event_id = ?;"
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, eventId)
            val rs = stmt.executeQuery()
            rs.next()
        }
    }

    suspend fun processPaymentEvent(
        event: WebhookEvent,
        rawPayload: String
    ): Boolean = DatabaseFactory.query { conn ->
        // 1. Deduplication check: return 200 no-op if event was already processed
        val checkEventSql = "SELECT event_id FROM processed_webhooks WHERE event_id = ? FOR UPDATE;"
        conn.prepareStatement(checkEventSql).use { stmt ->
            stmt.setString(1, event.event_id)
            if (stmt.executeQuery().next()) {
                return@query true // Idempotent 200 no-op
            }
        }

        // 2. If claim_id is null, record as UNKNOWN_CLAIM and acknowledge 200
        val claimId = event.claim_id
        if (claimId == null) {
            recordProcessedWebhook(conn, event.event_id, null, "UNKNOWN_CLAIM", rawPayload)
            return@query true
        }

        // 3. Pessimistic row-level lock on the claim to serialize against concurrent expiry or webhooks
        val selectClaimSql = """
            SELECT id, deal_id, customer_id, quantity, amount_paise, status, expires_at
            FROM claims
            WHERE id = ?
            FOR UPDATE;
        """.trimIndent()

        val claim = conn.prepareStatement(selectClaimSql).use { stmt ->
            stmt.setObject(1, claimId)
            val rs = stmt.executeQuery()
            if (rs.next()) rs.toClaimResponse() else null
        }

        if (claim == null) {
            recordProcessedWebhook(conn, event.event_id, null, "UNKNOWN_CLAIM", rawPayload)
            return@query true
        }

        // 4. State Machine Transitions according to candidate brief:
        val now = Instant.now()
        val isExpired = now.isAfter(claim.expires_at) || claim.status == ClaimStatus.EXPIRED

        val nextStatus = when (event.type) {
            "payment.failed" -> claim.status // Rule: No change; customer may pay again until expiry
            "payment.succeeded" -> {
                when {
                    isExpired -> {
                        // Rule: Late payment arriving past expires_at -> REFUND_PENDING (units remain in stock)
                        ClaimStatus.REFUND_PENDING
                    }
                    claim.status == ClaimStatus.RESERVED -> {
                        if (event.amount_paise == claim.amount_paise) {
                            ClaimStatus.PAID
                        } else {
                            // Rule: Amount differs -> NEEDS_REVIEW (units stay held for manual review)
                            ClaimStatus.NEEDS_REVIEW
                        }
                    }
                    else -> claim.status // Already in terminal state (PAID, NEEDS_REVIEW, REFUND_PENDING)
                }
            }
            else -> claim.status
        }

        // 5. Update claim status if transition occurred
        if (nextStatus != claim.status) {
            val updateClaimSql = """
                UPDATE claims 
                SET status = ?, updated_at = NOW()
                WHERE id = ?;
            """.trimIndent()
            conn.prepareStatement(updateClaimSql).use { stmt ->
                stmt.setString(1, nextStatus.name)
                stmt.setObject(2, claimId)
                stmt.executeUpdate()
            }
        }

        // 6. Record processed webhook event for deduplication
        recordProcessedWebhook(conn, event.event_id, claimId, nextStatus.name, rawPayload)
        true
    }

    private fun recordProcessedWebhook(
        conn: java.sql.Connection,
        eventId: String,
        claimId: UUID?,
        status: String,
        rawPayload: String
    ) {
        val sql = """
            INSERT INTO processed_webhooks (event_id, claim_id, status, raw_payload, processed_at)
            VALUES (?, ?, ?, ?, NOW())
            ON CONFLICT (event_id) DO NOTHING;
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, eventId)
            stmt.setObject(2, claimId)
            stmt.setString(3, status)
            stmt.setString(4, rawPayload)
            stmt.executeUpdate()
        }
    }

    private fun ResultSet.toClaimResponse(): ClaimResponse = ClaimResponse(
        id = getObject("id", UUID::class.java),
        deal_id = getObject("deal_id", UUID::class.java),
        customer_id = getObject("customer_id", UUID::class.java),
        quantity = getInt("quantity"),
        amount_paise = getInt("amount_paise"),
        status = ClaimStatus.valueOf(getString("status")),
        expires_at = getTimestamp("expires_at").toInstant()
    )
}
