package zio.entity.postgres.snapshot

import io.getquill.{PostgresZioJdbcContext, SnakeCase}
import zio.blocking.Blocking
import zio.entity.core.snapshot.KeyValueStore
import zio.entity.serializer.{SchemaCodec, SchemaDecoder, SchemaEncoder}
import zio.{Chunk, Task, ZLayer}

import java.sql.Connection

class PostgresqlKeyValueStore[Key: SchemaEncoder, Value: SchemaCodec](connection: Connection, blocking: Blocking.Service) extends KeyValueStore[Key, Value] {
  import MyPostgresContext._

  private val layer = ZLayer.succeed(connection) and ZLayer.succeed(blocking)

  override def setValue(key: Key, value: Value): Task[Unit] = {
    run(quote {
      query[(Chunk[Byte], Chunk[Byte])].insert((lift(SchemaEncoder[Key].encode(key)), lift(SchemaCodec[Value].encode(value))))
    }).unit.provideLayer(layer)
  }

  override def getValue(key: Key): Task[Option[Value]] = {
    val keyArray: Chunk[Byte] = SchemaEncoder[Key].encode(key)
    run(quote {
      query[(Chunk[Byte], Chunk[Byte])].filter { record =>
        val data: Chunk[Byte] = record._1
        data == lift(keyArray)
      }
    }).map(_.headOption.flatMap(res => SchemaDecoder[Value].decode(res._2).toOption)).provideLayer(layer)
  }

  override def deleteValue(key: Key): Task[Unit] = {
    val keyArray: Chunk[Byte] = SchemaEncoder[Key].encode(key)
    run(quote {
      query[(Chunk[Byte], Chunk[Byte])].filter(_._1 == lift(keyArray)).delete
    }).unit.provideLayer(layer)
  }
}

object MyPostgresContext extends PostgresZioJdbcContext(SnakeCase)
