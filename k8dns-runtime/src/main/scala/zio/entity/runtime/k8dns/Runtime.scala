package zio.entity.runtime.k8dns

import izumi.reflect.Tag
import zio.clock.Clock
import zio.duration.Duration
import zio.entity.core._
import zio.entity.core.journal.CommittableJournalQuery
import zio.entity.data.{CommandResult, EntityProtocol, Tagging}
import zio.entity.readside._
import zio.entity.runtime.k8dns.protocol.{GrpcRequestsNodeMessagingProtocol, NodeMessagingProtocol}
import zio.memberlist.Memberlist.SwimEnv
import zio.memberlist.{Memberlist, NodeAddress}
import zio.stream.ZStream
import zio.{memberlist, Chunk, Has, Ref, Task, UIO, ZIO, ZLayer, ZManaged}

import java.time.Instant
import java.util.UUID

object Runtime {

  def entityLive[Key: StringDecoder: StringEncoder: Tag, Algebra, State: Tag, Event: Tag, Reject: Tag](
    typeName: String,
    tagging: Tagging[Key],
    eventSourcedBehaviour: EventSourcedBehaviour[Algebra, State, Event, Reject]
  )(implicit
    protocol: EntityProtocol[Algebra, Reject]
  ): ZIO[Clock with Has[RuntimeServer] with Has[Stores[Key, Event, State]], Throwable, Entity[
    Key,
    Algebra,
    State,
    Event,
    Reject
  ]] = {

    def readSubscription(clock: Clock.Service, runtimeServer: RuntimeServer, committableJournalQuery: CommittableJournalQuery[Long, Key, Event]): (
      ReadSideParams[Key, Event, Reject],
      Throwable => Reject
    ) => ZStream[Any, Reject, KillSwitch] = (readSideParams, errorHandler) =>
      (for {
        runtimeServer <- ZStream.service[RuntimeServer]
        res <- ReadSideProcessor
          .readSideStream[Key, Event, Long, Reject](
            readSideParams,
            errorHandler,
            clock,
            runtimeServer.readSideProcessing,
            committableJournalQuery
          )
      } yield res).provideLayer(ZLayer.succeed(runtimeServer))

    for {
      runtimeServer <- ZIO.service[RuntimeServer]
      stores        <- ZIO.service[Stores[Key, Event, State]]
      clock         <- ZIO.service[Clock.Service]
      combinatorsMap = runtimeServer.expiringCache
      combinators = AlgebraCombinatorConfig[Key, State, Event](
        stores.offsetStore,
        tagging,
        stores.journalStore,
        stores.snapshotting
      )
      onMessageReceive = { (swimMessage: Message, sendFn: Message => Task[Unit]) =>
        val key = implicitly[StringDecoder[Key]].decode(swimMessage.key)
        val algebraCombinators: ZIO[Any, Throwable, Combinators[State, Event, Reject]] = for {
          keyToUse <- ZIO.fromOption(key).mapError(_ => new Exception("Cannot decode key"))
          keyInStringForm = implicitly[StringEncoder[Key]].encode(keyToUse)
          cache <- combinatorsMap.get(keyInStringForm)
          combinatorRetrieved <- cache match {
            case Some(combinator) =>
              UIO.succeed(combinator.asInstanceOf[Combinators[State, Event, Reject]])
            case None =>
              KeyedAlgebraCombinators
                .fromParams[Key, State, Event, Reject](keyToUse, eventSourcedBehaviour.eventHandler, eventSourcedBehaviour.errorHandler, combinators)
                .flatMap { combinator =>
                  combinatorsMap.add(keyInStringForm -> combinator).as(combinator)
                }
          }
        } yield combinatorRetrieved
        // if empty create combinator and set in the cache
        for {
          combinators <- algebraCombinators
          // TODO: if nodeAddress is localAddress, then we can avoid serialization and message passing (we can avoid the protocol)
          // TODO: send the error through the wire
          result <- protocol.server
            .apply(eventSourcedBehaviour.algebra(combinators), eventSourcedBehaviour.errorHandler)
            .call(swimMessage.payload)
          _ <- sendFn(swimMessage.copy(payload = result))
        } yield ()
      }
      _ <- runtimeServer.registerListener(typeName, onMessageReceive)

    } yield KeyAlgebraSender.keyToAlgebra[Key, Algebra, State, Event, Reject](readSubscription(clock, runtimeServer, stores.committableJournalStore))(
      { (key, payload) =>
        val keyString = implicitly[StringEncoder[Key]].encode(key)
        // if sending to local, we can return the algebra directly but we need to understand early the destination of the message asking it to the runtime
        runtimeServer.ask(keyString, typeName, payload).map(CommandResult)
      },
      eventSourcedBehaviour.errorHandler
    )
  }

}

trait RuntimeServer {

  def readSideProcessing: ReadSideProcessing

  def expiringCache: ExpiringCache[String, Combinators[_, _, _]]

  def registerListener(entityType: String, messageReceivedForMe: (Message, Message => Task[Unit]) => ZIO[Any, Throwable, Unit]): UIO[Unit]

  def ask(key: String, entityType: String, payload: Chunk[Byte]): Task[Chunk[Byte]]

}

case class Message(key: String, entityType: String, correlationId: UUID, payload: Chunk[Byte], createdAt: Instant) {
  def entityTypeAndId: String = s"${entityType}_$key"
}

//TODO: use different method to communicate, like grpc instead of memberlist udp protocol, put a timer in the message put in the queue and expire it accordingly with the timeout
object SwimRuntimeServer {

  implicit class RichNodeAddress(nodeAddress: NodeAddress) {
    def toIpString: String = {
      nodeAddress.ip.map { el => if (el < 0) 256 + el else el }.mkString(".")
    }

  }

  def live(
    connectionTimeout: Duration,
    expireAfter: Duration,
    checkEvery: Duration
  ): ZLayer[SwimEnv, Throwable, Has[RuntimeServer]] =
    (for {
      swim                  <- ZManaged.service[Memberlist.Service[Byte]]
      clock                 <- ZManaged.service[Clock.Service]
      nodeMessagingProtocol <- ZManaged.service[NodeMessagingProtocol]
      newExpiringCache      <- ZManaged.fromEffect(ExpiringCache.build[String, Combinators[_, _, _]](expireAfter, checkEvery))
      listeners             <- Ref.make[Map[String, (Message, Message => Task[Unit]) => Task[Unit]]](Map.empty).toManaged_
      _ <- nodeMessagingProtocol.receive
        .mapMPartitioned(el => el._2.entityType, 32) { case (nodeAddress, message, sendFn) =>
          zio.clock.instant.flatMap { now =>
            if (message.createdAt.toEpochMilli + connectionTimeout.toMillis < now.toEpochMilli) UIO.unit
            else
              for {
                listener <- listeners.get
                currentListener = listener.get(message.entityType)
                _ <- currentListener.get.apply(message, sendFn)
              } yield ()
          }
        }
        .runDrain
        .toManaged_
        .fork

    } yield new RuntimeServer {

      val readSideProcessing: ReadSideProcessing = new SwimReadSideProcessing(swim, clock)
      // subscribe to hub that receive message, has a cache of combinators by entity and key and has an eviction strategy

      // needs an answer
      override def ask(key: String, entityType: String, payload: Chunk[Byte]): Task[Chunk[Byte]] = {
        (for {
          activeNodes <- memberlist.nodes[Byte]
          nodesToUse  <- if (activeNodes.isEmpty) memberlist.localMember[Byte].map(Set(_)) else UIO.succeed(activeNodes)
          _           <- nodeMessagingProtocol.updateConnections(nodesToUse)
          now         <- zio.clock.instant
          nodeToUse = ShardLogic.getShardNode(key, nodesToUse.toList)
          uuid <- Task.effectTotal(UUID.randomUUID())
          element <- nodeMessagingProtocol
            .ask(
              nodeToUse,
              Message(key = key, entityType = entityType, payload = payload, correlationId = uuid, createdAt = now)
            )
            .map(_.payload)
        } yield element).provideLayer(ZLayer.succeed(swim) and ZLayer.succeed(clock))
      }

      override def registerListener(
        entityType: String,
        messageReceivedForMe: (Message, Message => Task[Unit]) => ZIO[Any, Throwable, Unit]
      ): UIO[Unit] =
        listeners.update { old =>
          old + (entityType -> messageReceivedForMe)
        }

      val expiringCache: ExpiringCache[String, Combinators[_, _, _]] = newExpiringCache
    }).provideSomeLayer[SwimEnv](Memberlist.live[Byte] ++ GrpcRequestsNodeMessagingProtocol.live).toLayer
}
