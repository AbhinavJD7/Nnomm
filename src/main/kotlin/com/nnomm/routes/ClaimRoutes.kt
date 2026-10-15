package com.nnomm.routes

import com.nnomm.domain.ClaimRequest
import com.nnomm.service.ClaimExecutionResult
import com.nnomm.service.ClaimService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.claimRoutes() {
    route("/v1") {
        post("/deals/{dealId}/claims") {
            val user = call.extractAuthUser()
            val dealId = call.parameters["dealId"] ?: ""
            val idempotencyKey = call.request.headers["Idempotency-Key"]
            val rawBytes = call.receiveRawBytesWithLimit()
            val request = rawBytes.parseJson<ClaimRequest>()

            when (val result = ClaimService.createClaim(user, dealId, idempotencyKey, request)) {
                is ClaimExecutionResult.Created -> {
                    call.respond(HttpStatusCode.Created, result.claim)
                }
                is ClaimExecutionResult.Replayed -> {
                    call.response.headers.append("Idempotent-Replayed", "true")
                    call.respondText(
                        text = result.body,
                        contentType = ContentType.Application.Json,
                        status = HttpStatusCode.fromValue(result.statusCode)
                    )
                }
            }
        }

        get("/claims/{claimId}") {
            val user = call.extractAuthUser()
            val claimId = call.parameters["claimId"] ?: ""
            val claim = ClaimService.getClaim(user, claimId)
            call.respond(HttpStatusCode.OK, claim)
        }
    }
}
