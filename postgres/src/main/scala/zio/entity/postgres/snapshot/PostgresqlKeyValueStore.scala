package zio.entity.postgres.snapshot

import com.typesafe.config.Config
import io.getquill.context.ZioJdbc
import io.getquill.dsl.DynamicQueryDsl
import io.getquill.{EntityQuery, PostgresZioJdbcContext, SnakeCase}
import zio.blocking.Blocking
import zio.entity.core.snapshot.KeyValueStore
import zio.entity.serializer.{SchemaCodec, SchemaDecoder, SchemaEncoder}
import zio.{Chunk, Has, Tag, Task, ZIO, ZLayer, ZManaged}

import java.sql.Connection

class PostgresqlKeyValueStore[Key: SchemaEncoder, Value: SchemaCodec](connection: Connection, blocking: Blocking.Service, tableName: String)
    extends KeyValueStore[Key, Value] {
  import MyPostgresContext._

  private val layer = ZLayer.succeed(connection) and ZLayer.succeed(blocking)
  case class Record(key: Chunk[Byte], value: Chunk[Byte])
  //TODO: tablename must be dynamic
  private val records = quote(querySchema[Record]("d"))

  override def setValue(key: Key, value: Value): Task[Unit] = {
    val valueToInsert = Record(key = SchemaEncoder[Key].encode(key), value = SchemaCodec[Value].encode(value))
    run(quote {
      records.insert(lift(valueToInsert))
    }).unit.provideLayer(layer)
  }

  override def getValue(key: Key): Task[Option[Value]] = {
    val keyArray: Chunk[Byte] = SchemaEncoder[Key].encode(key)
    run(quote {
      records.filter { record =>
        val data: Chunk[Byte] = record.key
        data == lift(keyArray)
      }
    }).map(_.headOption.flatMap(res => SchemaDecoder[Value].decode(res.value).toOption)).provideLayer(layer)
  }

  override def deleteValue(key: Key): Task[Unit] = {
    val keyArray: Chunk[Byte] = SchemaEncoder[Key].encode(key)
    run(quote {
      records.filter(_.key == lift(keyArray)).delete
    }).unit.provideLayer(layer)
  }
}

object MyPostgresContext extends PostgresZioJdbcContext(SnakeCase)

object PostgresqlKeyValueStore {
  import io.getquill.context.ZioJdbc._
  def dataSource(config: Config): ZLayer[Blocking, Throwable, QConnection] = ZioJdbc.QDataSource.fromConfig(config) to QConnection.fromDataSource

  private def createTable(tableName: String): ZIO[Has[Connection], Throwable, Unit] = {
    ZIO.accessM[Has[Connection]] { layer =>
      val connection = layer.get
      val sqlCreate: String = s"""CREATE TABLE IF NOT EXISTS $tableName 
        (key bytea,
         value bytea)"""
      ZIO.effect {
        val stmt = connection.createStatement()
        stmt.execute(sqlCreate)
      }
    }
  }
  def live[Key: SchemaEncoder: Tag, Value: SchemaCodec: Tag](
    tableName: String
  ): ZLayer[Has[Connection] with Blocking, Throwable, Has[KeyValueStore[Key, Value]]] =
    (createTable(tableName) *> (for {
      connection <- ZIO.service[Connection]
      blocking   <- ZIO.service[Blocking.Service]
    } yield new PostgresqlKeyValueStore[Key, Value](connection, blocking, tableName))).toLayer[KeyValueStore[Key, Value]]
}
