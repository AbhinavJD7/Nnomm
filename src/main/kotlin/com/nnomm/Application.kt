package com.nnomm

import com.nnomm.config.AppConfig
import com.nnomm.config.DatabaseFactory
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun main() {
    embeddedServer(Netty, port = AppConfig.port, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    DatabaseFactory.init(AppConfig)

    environment.monitor.subscribe(ApplicationStopped) {
        DatabaseFactory.close()
    }

    install(ContentNegotiation) {
        jackson()
    }

    routing {
        get("/health") {
            call.respondText("Service is running!")
        }
    }
}
