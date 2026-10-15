package com.nnomm.routes

import com.nnomm.domain.CreateDealRequest
import com.nnomm.service.DealService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.dealRoutes() {
    route("/v1/deals") {
        post {
            val user = call.extractAuthUser()
            val rawBytes = call.receiveRawBytesWithLimit()
            val request = rawBytes.parseJson<CreateDealRequest>()
            val response = DealService.createDeal(user, request)
            call.respond(HttpStatusCode.Created, response)
        }

        get("/{dealId}") {
            val user = call.extractAuthUser()
            val dealId = call.parameters["dealId"] ?: ""
            val response = DealService.getDeal(user, dealId)
            call.respond(HttpStatusCode.OK, response)
        }
    }
}
