package com.nnomm

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.nnomm.config.AppConfig
import com.nnomm.config.DatabaseFactory
import com.nnomm.domain.ApiException
import com.nnomm.domain.ErrorCode
import com.nnomm.domain.ErrorResponse
import com.nnomm.routes.dealRoutes
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun main() {
    embeddedServer(Netty, port = AppConfig.port, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module(initDatabase: Boolean = true) {
    if (initDatabase) {
        DatabaseFactory.init(AppConfig)
    }

    environment.monitor.subscribe(ApplicationStopped) {
        if (initDatabase) {
            DatabaseFactory.close()
        }
    }

    install(ContentNegotiation) {
        jackson {
            registerModule(JavaTimeModule())
            disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        }
    }

    install(StatusPages) {
        exception<ApiException> { call, cause ->
            call.respond(cause.errorCode.httpStatus, cause.toErrorResponse())
        }
        exception<Throwable> { call, cause ->
            call.respond(
                ErrorCode.INTERNAL.httpStatus,
                ErrorResponse(ErrorCode.INTERNAL, cause.message ?: "An unexpected error occurred")
            )
        }
    }

    routing {
        get("/health") {
            call.respondText("Service is running!")
        }
        dealRoutes()
    }
}
