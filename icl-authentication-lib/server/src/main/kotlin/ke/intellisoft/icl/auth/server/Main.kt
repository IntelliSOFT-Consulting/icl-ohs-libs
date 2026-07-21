package ke.intellisoft.icl.auth.server

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import ke.intellisoft.icl.auth.IclOHSAuth
import ke.intellisoft.icl.auth.config.AuthConfig
import ke.intellisoft.icl.auth.persistence.DatabaseFactory

fun main() {
    val env = System.getenv()
    val config = AuthConfig.fromEnv(env)

    val dataSource = DatabaseFactory.dataSource(
        host = env.require("DB_HOST"),
        port = env.require("DB_PORT").toInt(),
        dbName = env.require("DB_NAME"),
        username = env.require("DB_USERNAME"),
        password = env.require("DB_PASSWORD")
    )

    // IclOHSAuth runs this library's Flyway migrations against dataSource and connects
    // Exposed to it - server/ no longer manages migrations itself.
    val auth = IclOHSAuth(config, dataSource)

    embeddedServer(Netty, port = env["PORT"]?.toInt() ?: 8080, host = "0.0.0.0") {
        authModule(config, auth)
    }.start(wait = true)
}

private fun Map<String, String>.require(key: String): String =
    this[key]?.takeIf { it.isNotBlank() }
        ?: throw IllegalStateException("Missing required env key: $key")
