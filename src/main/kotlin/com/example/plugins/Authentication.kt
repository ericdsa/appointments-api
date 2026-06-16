package com.example.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*

class ApiKeyAuthConfig {
    var apiKey: String = ""
}

val ApiKeyAuth = createApplicationPlugin("ApiKeyAuth", ::ApiKeyAuthConfig) {
    val validKey = pluginConfig.apiKey

    onCall { call ->
        val provided = call.request.headers["X-Api-Key"]
        if (provided == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Missing X-Api-Key header"))
            return@onCall
        }
        if (provided != validKey) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Invalid API key"))
            return@onCall
        }
    }
}
