package zio.entity.postgres.snapshot

import doobie.util.transactor.Transactor
import org.testcontainers.containers
import org.testcontainers.containers.PostgreSQLContainer
import zio.entity.core.snapshot.KeyValueStore
import zio.entity.postgres.example.{AValue, Key}
import zio.entity.serializer.protobuf.ProtobufCodecs._
import zio.test.Assertion.equalTo
import zio.test.environment.TestEnvironment
import zio.test.{assert, DefaultRunnableSpec, ZSpec}
import zio.{Has, Task, UIO, ZIO, ZLayer, ZManaged}

object PostgresqlKeyValueStoreSpec extends DefaultRunnableSpec {

  private val layer: ZLayer[Any, Throwable, Has[KeyValueStore[Key, AValue]]] =
    PostgresqlTestContainerManaged.transact to PostgresqlKeyValueStore.make[Key, AValue]("test")

  override def spec: ZSpec[TestEnvironment, Any] = suite("A postgres key value store")(
    testM("Can store and retrieve values from db") {
      (for {
        keyValueStore     <- ZIO.service[KeyValueStore[Key, AValue]]
        _                 <- keyValueStore.setValue(Key("key2"), AValue(5, "example5"))
        differentKeyValue <- keyValueStore.getValue(Key("key2"))
        _                 <- keyValueStore.setValue(Key("ok"), AValue(1, "example"))
        retrievedValue    <- keyValueStore.getValue(Key("ok"))
        _                 <- keyValueStore.setValue(Key("ok"), AValue(2, "example2"))
        updatedValue      <- keyValueStore.getValue(Key("ok"))
        _                 <- keyValueStore.deleteValue(Key("ok"))
        deletedValue      <- keyValueStore.getValue(Key("ok"))
      } yield (
        assert(differentKeyValue)(equalTo(Some(AValue(5, "example5")))) &&
        assert(retrievedValue)(equalTo(Some(AValue(1, "example")))) &&
        assert(updatedValue)(equalTo(Some(AValue(2, "example2")))) &&
        assert(deletedValue)(equalTo(None))
      )).provideCustomLayer(layer)
    }
  )
}

object PostgresqlTestContainerManaged {

  val containerManaged: ZManaged[Any, Throwable, containers.PostgreSQLContainer[_]] = ZManaged.make {
    val container = new PostgreSQLContainer("postgres:11.12")
    ZIO.effect(container.start()).as(container)
  } { el => UIO.succeed(el.stop()) }

  val transact: ZLayer[Any, Throwable, Has[Transactor[Task]]] = {
    (for {
      container <- containerManaged
      transact  <- PostgresTransact.transact(container.getJdbcUrl, container.getUsername, container.getPassword)
    } yield transact).toLayer
  }
}
