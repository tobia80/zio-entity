package zio.entity.runtime.k8dns

import izumi.reflect.Tag
import zio.clock.Clock
import zio.duration.{durationInt, Duration}
import zio.entity.core._
import zio.entity.data.{EntityProtocol, Tagging}
import zio.memberlist.{Memberlist, NodeAddress, Swim}
import zio.stream.{Sink, ZStream}
import zio.{memberlist, Chunk, Has, Ref, Task, UIO, ZHub, ZIO, ZLayer, ZManaged}

import java.util.UUID

object Runtime {

  //TODO needs a cache and an eviction time (passivation) for receive management
  // lazy passivation or active passivation?
  def entityLive[Key: StringDecoder: StringEncoder: Tag, Algebra, State: Tag, Event: Tag, Reject: Tag](
    typeName: String,
    tagging: Tagging[Key],
    eventSourcedBehaviour: EventSourcedBehaviour[Algebra, State, Event, Reject]
  )(implicit
    protocol: EntityProtocol[Algebra, State, Event, Reject]
  ): ZIO[Clock with Has[RuntimeServer] with Has[ExpiringCache[String, Combinators[_, _, _]]] with Has[Stores[Key, Event, State]], Throwable, Entity[
    Key,
    Algebra,
    State,
    Event,
    Reject
  ]] = {

    for {
      runtimeServer  <- ZIO.service[RuntimeServer]
      stores         <- ZIO.service[Stores[Key, Event, State]]
      combinatorsMap <- ZIO.service[ExpiringCache[String, Combinators[_, _, _]]]
      combinators = AlgebraCombinatorConfig[Key, State, Event](
        stores.offsetStore,
        tagging,
        stores.journalStore,
        stores.snapshotting
      )
      // TODO:
      messageReceivedForMe = { (nodeAddress: NodeAddress, swimMessage: SwimMessage) =>
        val key = implicitly[StringDecoder[Key]].decode(swimMessage.key)
        val algebraCombinators: ZIO[Any, Exception, Combinators[State, Event, Reject]] = for {
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
          result <- protocol.server
            .apply(eventSourcedBehaviour.algebra, eventSourcedBehaviour.errorHandler)
            .call(swimMessage.payload)
            .provideLayer(ZLayer.fromEffect(algebraCombinators))
          //TODO send it back
          _ <- runtimeServer.sendAndForget(nodeAddress, swimMessage.copy(response = true, payload = result))
        } yield ()
      }
      _ <- runtimeServer.registerListener(messageReceivedForMe)

    } yield KeyAlgebraSender.keyToAlgebra[Key, Algebra, State, Event, Reject](???)(
      { (key, payload) =>
        val keyString = implicitly[StringEncoder[Key]].encode(key)

        runtimeServer.send(keyString, payload)
      },
      eventSourcedBehaviour.errorHandler
    )
  }

}

trait RuntimeServer {
  def registerListener(messageReceivedForMe: (NodeAddress, SwimMessage) => ZIO[Any, Throwable, Unit]): UIO[Unit]

  def sendAndForget(nodeAddress: NodeAddress, swimMessage: SwimMessage): Task[Unit]

  def send(key: String, payload: Chunk[Byte]): Task[Chunk[Byte]]

}

case class SwimMessage(invocationId: UUID, response: Boolean, key: String, entityName: String, entityType: String, payload: Chunk[Byte])

object SwimRuntimeServer {
  val messageTimeout: Duration = 5.seconds
  val live: ZLayer[Swim[SwimMessage] with Clock, Throwable, Has[RuntimeServer]] = (for {
    swim                       <- ZManaged.service[Memberlist.Service[SwimMessage]]
    clock                      <- ZManaged.service[Clock.Service]
    hub                        <- ZHub.bounded[(NodeAddress, SwimMessage)](128).toManaged_
    _                          <- memberlist.receive[SwimMessage].run(Sink.fromHub(hub)).toManaged_
    listeners                  <- Ref.make[Map[String, (NodeAddress, SwimMessage) => UIO[Unit]]](Map.empty).toManaged_
    receiveMessageSubscription <- hub.subscribe
    _ <- ZStream
      .fromQueue(receiveMessageSubscription)
      .foreach { case (nodeAddress, swimMessage) =>
        for {
          listener <- listeners.get
          currentListener = listener.get(swimMessage.entityType)
          _ <- currentListener.get.apply(nodeAddress, swimMessage)
        } yield ()
        ???
      }
      .toManaged_
      .fork
  } yield new RuntimeServer {

    // subscribe to hub that receive message, has a cache of combinators by entity and key and has an eviction strategy

    private def getShardNode(key: String, nodes: List[NodeAddress]): NodeAddress = {
      val numberOfShards = nodes.size
      val nodeIndex: Int = scala.math.abs(key.hashCode) % numberOfShards
      nodes(nodeIndex)
    }

    override def send(key: String, payload: Chunk[Byte]): Task[Chunk[Byte]] = {
      (for {
        dequeue     <- hub.subscribe
        activeNodes <- memberlist.nodes[SwimMessage].toManaged_
        nodeToUse = getShardNode(key, activeNodes.toList)
        uuid = UUID.randomUUID()
        _ <- memberlist.send[SwimMessage](SwimMessage(uuid, false, "", "", "", payload), nodeToUse).toManaged_
        // TODO not sure if accumulates from subscribe or from stream
        element <- ZStream
          .fromQueue(dequeue.filterOutput { case (address, message) => message.invocationId.toString == uuid.toString && message.response })
          .runHead
          .timeout(messageTimeout)
          .map(_.flatten)
          .toManaged_
      } yield {
        element match {
          case Some((from, payload)) => payload.payload
          case None                  => Chunk.empty
        }
      }).provideLayer(ZLayer.succeed(swim) and ZLayer.succeed(clock))
    }.useNow

    override def sendAndForget(nodeAddress: NodeAddress, swimMessage: SwimMessage): Task[Unit] = ???

    override def registerListener(messageReceivedForMe: (NodeAddress, SwimMessage) => ZIO[Any, Throwable, Unit]): UIO[Unit] = ???
  }).toLayer
}
