package zio.entity.runtime.akka

import akka.actor.ActorSystem
import akka.cluster.sharding.{ClusterSharding, ShardRegion}
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import izumi.reflect.Tag
import scodec.bits.BitVector
import zio.duration.{durationInt, Duration}
import zio.entity.core._
import zio.entity.core.snapshot.Snapshotting
import zio.entity.data.{CommandInvocation, CommandResult, EntityProtocol, Tagging}
import zio.entity.runtime.akka.readside.ReadSideSettings
import zio.entity.runtime.akka.serialization.Message
import zio.{Has, IO, Managed, Task, ZIO, ZLayer}

import scala.concurrent.Future

object Runtime {

  case class KeyedCommand(key: String, bytes: BitVector) extends Message

  def actorSystemLayer(name: String, confFileName: String = "entity.conf"): ZLayer[Any, Throwable, Has[ActorSystem]] =
    ZLayer.fromManaged(
      Managed.make(ZIO.effect(ActorSystem(name, ConfigFactory.load(confFileName))))(sys => Task.fromFuture(_ => sys.terminate()).either)
    )

  def actorSettings(actorSystemName: String): ZLayer[Any, Throwable, Has[ActorSystem] with Has[ReadSideSettings] with Has[RuntimeSettings]] = {
    val actorSystem = actorSystemLayer(actorSystemName)
    val readSideSettings = actorSystem to ZLayer.fromService(ReadSideSettings.default)
    val runtimeSettings = actorSystem to ZLayer.fromService(RuntimeSettings.default)
    actorSystem and readSideSettings and runtimeSettings
  }

  def entityLive[Key: StringDecoder: StringEncoder: Tag, Algebra, State: Tag, Event: Tag, Reject: Tag](
    typeName: String,
    tagging: Tagging[Key],
    eventSourcedBehaviour: EventSourcedBehaviour[Algebra, State, Event, Reject],
    pollingInterval: Duration = 300.millis
  )(implicit
    protocol: EntityProtocol[Algebra, State, Event, Reject]
  ): ZIO[Has[ActorSystem] with Has[RuntimeSettings] with Has[StoresFactory[Key, Event, State]], Throwable, Entity[Key, Algebra, State, Event, Reject]] = {
    for {
      storesFactory <- ZIO.service[StoresFactory[Key, Event, State]]
      stores        <- storesFactory.buildStores(typeName, pollingInterval)
      combinators = AlgebraCombinatorConfig.build[Key, State, Event](
        stores.offsetStore,
        tagging,
        stores.journalStore,
        Snapshotting.eachVersion(2, stores.snapshotStore)
      )
      algebra <- buildEntity(typeName, eventSourcedBehaviour, combinators)
    } yield algebra
  }

  def buildEntity[Key: StringDecoder: StringEncoder: Tag, Algebra, State: Tag, Event: Tag, Reject: Tag](
    typeName: String,
    eventSourcedBehaviour: EventSourcedBehaviour[Algebra, State, Event, Reject],
    algebraCombinatorConfig: AlgebraCombinatorConfig[Key, State, Event]
  )(implicit
    protocol: EntityProtocol[Algebra, State, Event, Reject]
  ): ZIO[Has[ActorSystem] with Has[RuntimeSettings], Throwable, Entity[Key, Algebra, State, Event, Reject]] = ZIO.access { layer =>
    val system = layer.get[ActorSystem]
    val settings = layer.get[RuntimeSettings]
    val props = ZioEntityActor.props[Key, Algebra, State, Event, Reject](eventSourcedBehaviour, algebraCombinatorConfig)

    val extractEntityId: ShardRegion.ExtractEntityId = { case KeyedCommand(entityId, c) =>
      (entityId, CommandInvocation(c))
    }

    val numberOfShards = settings.numberOfShards

    val extractShardId: ShardRegion.ExtractShardId = {
      case KeyedCommand(key, _) =>
        String.valueOf(scala.math.abs(key.hashCode) % numberOfShards)
      case other => throw new IllegalArgumentException(s"Unexpected message [$other]")
    }

    val shardRegion = ClusterSharding(system).start(
      typeName = typeName,
      entityProps = props,
      settings = settings.clusterShardingSettings,
      extractEntityId = extractEntityId,
      extractShardId = extractShardId
    )

    val keyEncoder = StringEncoder[Key]

    // macro that creates bytes when method is invoked
    KeyAlgebraSender.keyToAlgebra(
      (key: Key, bytes: BitVector) => {
        IO.fromFuture[CommandResult] { _ =>
          implicit val askTimeout: Timeout = Timeout(settings.askTimeout)
          val result: Future[CommandResult] = (shardRegion ? KeyedCommand(keyEncoder(key), bytes)).mapTo[CommandResult]
          result
        }
      },
      eventSourcedBehaviour.errorHandler
    )
  }
}
