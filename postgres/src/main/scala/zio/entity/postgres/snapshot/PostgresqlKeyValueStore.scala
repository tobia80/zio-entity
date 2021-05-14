package zio.entity.postgres.snapshot

import com.typesafe.config.Config
import io.getquill.context.ZioJdbc
import io.getquill.context.ZioJdbc.{QConnection, QDataSource}
import io.getquill.{PostgresZioJdbcContext, SnakeCase}
import zio.blocking.Blocking
import zio.entity.core.snapshot.KeyValueStore
import zio.entity.serializer.{SchemaCodec, SchemaDecoder, SchemaEncoder}
import zio.{Chunk, Has, Tag, Task, ZIO, ZLayer, ZManaged}

import java.io.Closeable
import javax.sql.DataSource

class PostgresqlKeyValueStore[Key: SchemaEncoder, Value: SchemaCodec](datasource: DataSource with Closeable, blocking: Blocking.Service, tableName: String)
    extends KeyValueStore[Key, Value] {
  import MyPostgresContext._

  case class Record(key: Chunk[Byte], value: Chunk[Byte])
  //TODO: tablename must be dynamic
  private val records = dynamicQuerySchema[Record](tableName)
  private def layer: ZLayer[Any, Throwable, QConnection] =
    ZLayer.succeed(blocking) to ZioJdbc.QDataSource.fromDataSource(datasource) to QDataSource.toConnection

  override def setValue(key: Key, value: Value): Task[Unit] = {
    val valueToInsert = Record(key = SchemaEncoder[Key].encode(key), value = SchemaCodec[Value].encode(value))
    run(
      records.insertValue(valueToInsert)
    ).unit.provideLayer(layer)
  }

  override def getValue(key: Key): Task[Option[Value]] = {
    val keyArray: Chunk[Byte] = SchemaEncoder[Key].encode(key)
    run(
      records.filter { record =>
        quote {
          val data: Chunk[Byte] = record.key
          data == lift(keyArray)
        }
      }
    ).map(_.headOption.flatMap(res => SchemaDecoder[Value].decode(res.value).toOption)).provideLayer(layer)
  }

  override def deleteValue(key: Key): Task[Unit] = {
    val keyArray: Chunk[Byte] = SchemaEncoder[Key].encode(key)
    run(
      records.filter(_.key == lift(keyArray)).delete
    ).unit.provideLayer(layer)
  }
}

object MyPostgresContext extends PostgresZioJdbcContext(SnakeCase)

object PostgresqlKeyValueStore {
  import io.getquill.context.ZioJdbc._
  def dataSource(config: Config): ZLayer[Blocking, Throwable, QDataSource] = ZioJdbc.QDataSource.fromConfig(config)

  private def createTable(tableName: String): ZIO[QDataSource, Throwable, Unit] = {
    ZIO.accessM[Has[DataSource with Closeable]] { layer =>
      val datasource = layer.get
      val sqlCreate: String =
        s"""CREATE TABLE IF NOT EXISTS $tableName
        (key bytea,
         value bytea)"""
      //TODO close the connection
      ZManaged.fromAutoCloseable(ZIO.succeed(datasource.getConnection)).use { conn =>
        ZIO.effect {
          val stmt = conn.createStatement()
          stmt.execute(sqlCreate)
        }
      }
    }
  }
  def live[Key: SchemaEncoder: Tag, Value: SchemaCodec: Tag](
    tableName: String
  ): ZLayer[QDataSource, Throwable, Has[KeyValueStore[Key, Value]]] =
    (createTable(tableName) *> (for {
      datasource <- ZIO.service[DataSource with Closeable]
      blocking   <- ZIO.service[Blocking.Service]
    } yield new PostgresqlKeyValueStore[Key, Value](datasource, blocking, tableName))).toLayer[KeyValueStore[Key, Value]]
}
