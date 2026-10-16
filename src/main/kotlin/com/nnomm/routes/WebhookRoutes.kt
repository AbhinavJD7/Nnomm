package com.nnomm.routes

import com.nnomm.service.PaymentWebhookService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.webhookRoutes() {
    route("/v1/webhooks") {
        post("/payments") {
            val signatureHeader = call.request.headers["X-Payment-Signature"]
            val rawBytes = call.receiveRawBytesWithLimit()
            PaymentWebhookService.handleWebhook(signatureHeader, rawBytes)
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }
    }
}
