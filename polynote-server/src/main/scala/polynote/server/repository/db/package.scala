package polynote.server.repository.db

import io.getquill.{SnakeCase}

package object Database {
    type DbContext = PostgresJdbcContext[SnakeCase]
}
