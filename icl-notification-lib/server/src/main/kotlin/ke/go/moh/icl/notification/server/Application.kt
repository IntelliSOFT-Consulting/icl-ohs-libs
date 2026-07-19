package ke.go.moh.icl.notification.server

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import ke.go.moh.icl.notification.NotifyService
import ke.go.moh.icl.notification.template.TemplateNotFoundException
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("ke.go.moh.icl.notification.server.Application")

fun main() {
    val config = loadNotificationConfigFromEnv()
    val notifyService = buildNotifyService(config)
    embeddedServer(Netty, port = loadPortFromEnv(), module = { module(notifyService) }).start(wait = true)
}

fun Application.module(notifyService: NotifyService) {
    install(ContentNegotiation) { json() }
    install(CallLogging)
    install(StatusPages) {
        exception<TemplateNotFoundException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "unknown template"))
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "invalid request"))
        }
        exception<Throwable> { call, cause ->
            logger.error("[NotifyServer] ${cause.message}", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal error"))
        }
    }
    routing {
        notifyRoutes(notifyService)
    }
}

@Serializable
data class ErrorResponse(val error: String)
