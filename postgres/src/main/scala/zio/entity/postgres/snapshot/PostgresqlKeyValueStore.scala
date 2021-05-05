package zio.entity.postgres.snapshot

import io.getquill.{PostgresZioJdbcContext, SnakeCase}
import zio.Task
import zio.entity.core.snapshot.KeyValueStore

class PostgresqlKeyValueStore[Key, Value](name: String) extends KeyValueStore[Key, Value] {
  import MyPostgresContext._

  override def setValue(key: Key, value: Value): Task[Unit] = ???

  override def getValue(key: Key): Task[Option[Value]] = ???

  override def deleteValue(key: Key): Task[Unit] = ???
}

object MyPostgresContext extends PostgresZioJdbcContext(SnakeCase)
