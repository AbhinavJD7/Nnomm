package com.nnomm.repository

import com.nnomm.config.DatabaseFactory
import com.nnomm.domain.ApiException
import com.nnomm.domain.ClaimResponse
import com.nnomm.domain.ClaimStatus
import com.nnomm.domain.ErrorCode
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

object ClaimRepository {

    suspend fun executeClaimTransaction(
        dealId: UUID,
        customerId: UUID,
        quantity: Int,
        claimTtlSeconds: Long
    ): ClaimResponse = DatabaseFactory.query { conn ->
        // 1. Fetch deal details
        val dealSql = """
            SELECT id, restaurant_id, price_paise, stock_total, stock_available, starts_at, ends_at
            FROM deals
            WHERE id = ?;
        """.trimIndent()

        val pricePaise: Int
        conn.prepareStatement(dealSql).use { stmt ->
            stmt.setObject(1, dealId)
            val rs = stmt.executeQuery()
            if (!rs.next()) {
                throw ApiException(ErrorCode.NOT_FOUND, "Deal not found")
            }
            val startsAt = rs.getTimestamp("starts_at").toInstant()
            val endsAt = rs.getTimestamp("ends_at").toInstant()
            val now = Instant.now()

            // Rule 3: Claims only while deal is live (starts_at up to, but not including, ends_at)
            if (now.isBefore(startsAt) || !now.isBefore(endsAt)) {
                throw ApiException(ErrorCode.DEAL_NOT_LIVE, "The deal hasn't started yet, or has ended")
            }
            pricePaise = rs.getInt("price_paise")
        }

        // 2. Rule 2: At most one active claim per customer per deal (RESERVED, PAID, NEEDS_REVIEW)
        val activeClaimSql = """
            SELECT id FROM claims 
            WHERE deal_id = ? AND customer_id = ? AND status IN ('RESERVED', 'PAID', 'NEEDS_REVIEW');
        """.trimIndent()
        conn.prepareStatement(activeClaimSql).use { stmt ->
            stmt.setObject(1, dealId)
            stmt.setObject(2, customerId)
            val rs = stmt.executeQuery()
            if (rs.next()) {
                throw ApiException(ErrorCode.ALREADY_CLAIMED, "The customer already holds an active claim on this deal")
            }
        }

        // 3. Rule 1: Atomic stock deduction (prevents overselling across both instances)
        val deductSql = """
            UPDATE deals
            SET stock_available = stock_available - ?
            WHERE id = ? 
              AND stock_available >= ? 
              AND starts_at <= NOW() 
              AND ends_at > NOW();
        """.trimIndent()

        val updatedRows = conn.prepareStatement(deductSql).use { stmt ->
            stmt.setInt(1, quantity)
            stmt.setObject(2, dealId)
            stmt.setInt(3, quantity)
            stmt.executeUpdate()
        }

        if (updatedRows == 0) {
            throw ApiException(ErrorCode.SOLD_OUT, "Not enough units left for this quantity")
        }

        // 4. Create claim row
        val now = Instant.now()
        val expiresAt = now.plusSeconds(claimTtlSeconds)
        val amountPaise = pricePaise * quantity

        val insertClaimSql = """
            INSERT INTO claims (deal_id, customer_id, quantity, amount_paise, status, expires_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, 'RESERVED', ?, ?, ?)
            RETURNING id, deal_id, customer_id, quantity, amount_paise, status, expires_at;
        """.trimIndent()

        try {
            conn.prepareStatement(insertClaimSql).use { stmt ->
                stmt.setObject(1, dealId)
                stmt.setObject(2, customerId)
                stmt.setInt(3, quantity)
                stmt.setInt(4, amountPaise)
                stmt.setTimestamp(5, Timestamp.from(expiresAt))
                stmt.setTimestamp(6, Timestamp.from(now))
                stmt.setTimestamp(7, Timestamp.from(now))

                val rs = stmt.executeQuery()
                rs.next()
                rs.toClaimResponse()
            }
        } catch (e: Exception) {
            if (e.message?.contains("uq_active_claim_per_customer") == true) {
                throw ApiException(ErrorCode.ALREADY_CLAIMED, "The customer already holds an active claim on this deal")
            }
            throw e
        }
    }

    suspend fun findClaimWithDealInfo(claimId: UUID): Pair<ClaimResponse, UUID>? = DatabaseFactory.query { conn ->
        val sql = """
            SELECT c.id, c.deal_id, c.customer_id, c.quantity, c.amount_paise, c.status, c.expires_at, d.restaurant_id
            FROM claims c
            JOIN deals d ON c.deal_id = d.id
            WHERE c.id = ?;
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setObject(1, claimId)
            val rs = stmt.executeQuery()
            if (rs.next()) {
                val claim = rs.toClaimResponse()
                val restaurantId = rs.getObject("restaurant_id", UUID::class.java)
                claim to restaurantId
            } else null
        }
    }

    suspend fun findById(claimId: UUID): ClaimResponse? = findClaimWithDealInfo(claimId)?.first

    private fun ResultSet.toClaimResponse(): ClaimResponse {
        val rawStatus = getString("status")
        val expiresAt = getTimestamp("expires_at").toInstant()

        // Rule: "From expires_at on, the API never shows it as RESERVED"
        val status = if (rawStatus == "RESERVED" && Instant.now().isAfter(expiresAt)) {
            ClaimStatus.EXPIRED
        } else {
            ClaimStatus.valueOf(rawStatus)
        }

        return ClaimResponse(
            id = getObject("id", UUID::class.java),
            deal_id = getObject("deal_id", UUID::class.java),
            customer_id = getObject("customer_id", UUID::class.java),
            quantity = getInt("quantity"),
            amount_paise = getInt("amount_paise"),
            status = status,
            expires_at = expiresAt
        )
    }
}
