package zio.entity.postgres.snapshot

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.typesafe.config.ConfigFactory
import io.getquill.JdbcContextConfig
import io.getquill.context.ZioJdbc.QDataSource
import org.testcontainers.containers
import zio.blocking.Blocking
import zio.entity.core.snapshot.KeyValueStore
import zio.entity.postgres.example.{AValue, Key}
import zio.test.Assertion.equalTo
import zio.test.environment.TestEnvironment
import zio.test.{assert, DefaultRunnableSpec, ZSpec}
import zio.{Has, UIO, ZIO, ZLayer, ZManaged}

import zio.entity.serializer.protobuf.ProtobufCodecs._

object PostgresqlKeyValueStoreSpec extends DefaultRunnableSpec {

  private val layer: ZLayer[Blocking, Throwable, Has[KeyValueStore[Key, AValue]]] =
    PostgresqlTestContainerManaged.dataSource to PostgresqlKeyValueStore.live[Key, AValue]("test")

  override def spec: ZSpec[TestEnvironment, Any] = suite("A postgres key value store")(
    testM("Can store and retrieve values from db") {
      (for {
        keyValueStore  <- ZIO.service[KeyValueStore[Key, AValue]]
        _              <- keyValueStore.setValue(Key("ok"), AValue(1, "example"))
        retrievedValue <- keyValueStore.getValue(Key("ok"))
      } yield (
        assert(retrievedValue)(equalTo(Some(AValue(1, "example"))))
      )).provideCustomLayer(layer)
    }
  )
}

object PostgresqlTestContainerManaged {

  val containerManaged: ZManaged[Any, Throwable, containers.PostgreSQLContainer[_]] = ZManaged.make {
    val container = PostgreSQLContainer().container
    ZIO.effect(container.start()).as(container)
  } { el => UIO.succeed(el.stop()) }

  val dataSource: ZLayer[Blocking, Throwable, QDataSource] = {
    import scala.jdk.CollectionConverters._
    (for {
      container <- containerManaged
      config = JdbcContextConfig(
        ConfigFactory.parseMap(
          Map(
            "username"     -> container.getUsername,
            "password"     -> container.getPassword,
            "jdbcUrl"      -> container.getJdbcUrl,
          ).asJava
        )
      )
      datasource <- QDataSource.Managed.fromDataSource(config.dataSource)
    } yield datasource.get).toLayer and ZLayer.requires[Blocking]
  }
}
