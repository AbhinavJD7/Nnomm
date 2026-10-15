package com.nnomm.repository

import com.nnomm.config.DatabaseFactory
import com.nnomm.domain.DealResponse
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

object DealRepository {

    suspend fun ensureRestaurantExists(restaurantId: UUID) = DatabaseFactory.query { conn ->
        val sql = """
            INSERT INTO restaurants (id, name)
            VALUES (?, 'Restaurant')
            ON CONFLICT (id) DO NOTHING;
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setObject(1, restaurantId)
            stmt.executeUpdate()
        }
    }

    suspend fun createDeal(
        restaurantId: UUID,
        title: String,
        pricePaise: Int,
        stockTotal: Int,
        startsAt: Instant,
        endsAt: Instant
    ): DealResponse = DatabaseFactory.query { conn ->
        val sql = """
            INSERT INTO deals (restaurant_id, title, price_paise, stock_total, stock_available, starts_at, ends_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            RETURNING id, restaurant_id, title, price_paise, stock_total, stock_available, starts_at, ends_at;
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setObject(1, restaurantId)
            stmt.setString(2, title)
            stmt.setInt(3, pricePaise)
            stmt.setInt(4, stockTotal)
            stmt.setInt(5, stockTotal) // Initially, stock_available == stock_total
            stmt.setTimestamp(6, Timestamp.from(startsAt))
            stmt.setTimestamp(7, Timestamp.from(endsAt))

            val rs = stmt.executeQuery()
            rs.next()
            rs.toDealResponse()
        }
    }

    suspend fun findById(dealId: UUID): DealResponse? = DatabaseFactory.query { conn ->
        val sql = """
            SELECT id, restaurant_id, title, price_paise, stock_total, stock_available, starts_at, ends_at
            FROM deals
            WHERE id = ?;
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setObject(1, dealId)
            val rs = stmt.executeQuery()
            if (rs.next()) rs.toDealResponse() else null
        }
    }

    private fun ResultSet.toDealResponse(): DealResponse = DealResponse(
        id = getObject("id", UUID::class.java),
        restaurant_id = getObject("restaurant_id", UUID::class.java),
        title = getString("title"),
        price_paise = getInt("price_paise"),
        stock_total = getInt("stock_total"),
        stock_available = getInt("stock_available"),
        starts_at = getTimestamp("starts_at").toInstant(),
        ends_at = getTimestamp("ends_at").toInstant()
    )
}
