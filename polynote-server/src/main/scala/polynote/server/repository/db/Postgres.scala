package polynote.server.repository.db

import java.util

import com.typesafe.config.ConfigValueFactory
import com.typesafe.scalalogging.LazyLogging
import io.getquill.SnakeCase
import polynote.config.PolynoteConfig
import polynote.server.repository.db.Database.DbContext

/**
 * This class configures and defines the DbContext for JDBC
 * access to the Postgres database.
 */
class Postgres(config: PolynoteConfig) extends LazyLogging {

    val DriverClassName = "org.postgresql.Driver"
    lazy val dataSourceClassName = "org.postgresql.ds.PGPoolingDataSource"

    private val host = scala.util.Properties.envOrElse("DATABASE_HOST", config.databaseConfig.host.get)
    private val port = scala.util.Properties.envOrElse("DATABASE_PORT", config.databaseConfig.port.get.toString)
    private val database = scala.util.Properties.envOrElse("DATABASE_NAME", config.databaseConfig.databaseName.get)
    private val username = scala.util.Properties.envOrElse("DATABASE_USER", config.databaseConfig.user.get)
    private val password = scala.util.Properties.envOrElse("DATABASE_PASSWORD", config.databaseConfig.password.get)

    val dbConfig = {
        val configMap = new util.HashMap[String, Object]
        configMap.put("dataSourceClassName", dataSourceClassName)
        configMap.put("minimumIdle", new Integer(config.databaseConfig.minimumIdleConnections.get))
        configMap.put("maximumPoolSize", new Integer(config.databaseConfig.maximumConnectionPoolSize.get))

        val dataSourceMap = new util.HashMap[String, Object]
        configMap.put("dataSource", dataSourceMap)

        dataSourceMap.put("user", username)
        dataSourceMap.put("password", password)
        dataSourceMap.put("portNumber", new Integer(port))
        dataSourceMap.put("serverName", host)
        dataSourceMap.put("databaseName", database)

        ConfigValueFactory.fromMap(configMap).toConfig
    }

    lazy val url = s"jdbc:postgresql://$host:$port/$database?user=$username&password=$password"

    def init() = {
        val context = new DbContext(SnakeCase, dbConfig)
        sys.addShutdownHook(context.close())
        context
    }
}
