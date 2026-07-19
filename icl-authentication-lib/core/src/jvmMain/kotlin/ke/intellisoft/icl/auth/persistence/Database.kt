package ke.intellisoft.icl.auth.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database as ExposedDatabase
import javax.sql.DataSource

/** One HikariCP pool per process, reused by the server module and by integration tests. */
object DatabaseFactory {

    fun dataSource(host: String, port: Int, dbName: String, username: String, password: String): DataSource {
        val config = HikariConfig().apply {
            jdbcUrl = "jdbc:postgresql://$host:$port/$dbName"
            this.username = username
            this.password = password
            maximumPoolSize = 10
        }
        return HikariDataSource(config)
    }

    fun connect(dataSource: DataSource): Unit {
        ExposedDatabase.connect(dataSource)
    }
}
