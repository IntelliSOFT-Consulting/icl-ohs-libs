package ke.go.moh.icl.notification.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import ke.go.moh.icl.notification.NotifyService
import ke.go.moh.icl.notification.model.BroadcastRequest
import ke.go.moh.icl.notification.model.EmailRequest
import ke.go.moh.icl.notification.model.SmsRequest
import kotlinx.serialization.Serializable

@Serializable
data class TemplatesResponse(val templates: List<String>)

fun Route.notifyRoutes(notifyService: NotifyService) {
    route("/api/v1/notify") {
        post("/email") {
            val request = call.receive<EmailRequest>()
            val result = notifyService.sendEmail(request)
            call.respond(if (result.success) HttpStatusCode.OK else HttpStatusCode.BadGateway, result)
        }

        post("/sms") {
            val request = call.receive<SmsRequest>()
            val result = notifyService.sendSms(request)
            call.respond(if (result.success) HttpStatusCode.OK else HttpStatusCode.BadGateway, result)
        }

        post("/broadcast") {
            val request = call.receive<BroadcastRequest>()
            val result = notifyService.broadcast(request)
            call.respond(HttpStatusCode.OK, result)
        }

        get("/templates") {
            call.respond(HttpStatusCode.OK, TemplatesResponse(notifyService.templates()))
        }
    }
}
