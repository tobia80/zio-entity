package zio.entity.postgres.snapshot

import cats.effect.Blocker
import doobie.{Fragment, Update0}
import doobie.hikari.HikariTransactor
import doobie.implicits.{toSqlInterpolator, _}
import doobie.util.transactor.Transactor
import zio._
import zio.entity.core.snapshot.KeyValueStore
import zio.entity.serializer.{SchemaCodec, SchemaDecoder, SchemaEncoder}
import zio.interop.catz._

class PostgresqlKeyValueStore[Key: SchemaEncoder, Value: SchemaCodec](
  transactor: Transactor[Task],
  tableName: String
) extends KeyValueStore[Key, Value] {

  case class Record(key: Array[Byte], value: Array[Byte])
  //TODO: tablename must be dynamic

  override def setValue(key: Key, value: Value): Task[Unit] = {
    val valueToInsert = Record(key = SchemaEncoder[Key].encode(key).toArray, value = SchemaCodec[Value].encode(value).toArray)
    //ON CONFLICT (key) DO UPDATE SET value = $value;
    (fr"INSERT INTO " ++ Fragment.const(
      tableName
    ) ++ fr"(key, value) VALUES(${valueToInsert.key}, ${valueToInsert.value}) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value").update.run
      .transact(transactor)
      .unit
  }

  override def getValue(key: Key): Task[Option[Value]] = {
    val keyArray: Array[Byte] = SchemaEncoder[Key].encode(key).toArray
    (fr"select * from " ++ Fragment.const(tableName) ++ fr" where key = $keyArray")
      .query[Record]
      .option
      .transact(transactor)
      .map { res =>
        res.flatMap { element =>
          implicitly[SchemaDecoder[Value]].decode(Chunk.fromArray(element.value)).toOption
        }
      }
  }

  override def deleteValue(key: Key): Task[Unit] = {
    val keyArray: Array[Byte] = SchemaEncoder[Key].encode(key).toArray
    (fr"DELETE FROM " ++ Fragment.const(tableName) ++ fr" where KEY = $keyArray").update.run.transact(transactor).unit
  }
}

object PostgresqlKeyValueStore {

  private def createTable(tableName: String, transactor: Transactor[Task]): Task[Unit] = {
    Update0(
      s"""CREATE TABLE IF NOT EXISTS $tableName (
             key BYTEA PRIMARY KEY, value BYTEA NOT NULL
         )""",
      None
    ).run.transact(transactor).unit
  }

  def transact(url: String, user: String, password: String): ZManaged[Any, Throwable, Transactor[Task]] = {
    for {
      runtime <- ZIO.runtime[Any].toManaged_
      hikari <- HikariTransactor
        .newHikariTransactor[Task](
          "org.postgresql.Driver",
          url,
          user,
          password,
          runtime.platform.executor.asEC,
          Blocker.liftExecutionContext(runtime.platform.executor.asEC)
        )
        .toManagedZIO
    } yield hikari
  }

  def live[Key: SchemaEncoder: Tag, Value: SchemaCodec: Tag](
    tableName: String
  ): ZLayer[Has[Transactor[Task]], Throwable, Has[KeyValueStore[Key, Value]]] = {
    ZIO.accessM[Has[Transactor[Task]]] { lay =>
      val xa = lay.get
      createTable(tableName, xa) *> ZIO.effect(new PostgresqlKeyValueStore[Key, Value](xa, tableName))
    }
  }.toLayer
}
