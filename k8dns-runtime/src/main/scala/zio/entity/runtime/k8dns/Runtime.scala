package zio.entity.runtime.k8dns

import izumi.reflect.Tag
import zio.clock.Clock
import zio.duration.{durationInt, Duration}
import zio.entity.core._
import zio.entity.core.journal.CommittableJournalQuery
import zio.entity.data.{EntityProtocol, Tagging}
import zio.entity.readside.{KillSwitch, ReadSideParams, ReadSideProcess, ReadSideProcessing, ReadSideProcessor}
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
      runtimeServer  <- ZIO.service[RuntimeServer]
      stores         <- ZIO.service[Stores[Key, Event, State]]
      clock          <- ZIO.service[Clock.Service]
      combinatorsMap <- ZIO.service[ExpiringCache[String, Combinators[_, _, _]]]
      combinators = AlgebraCombinatorConfig[Key, State, Event](
        stores.offsetStore,
        tagging,
        stores.journalStore,
        stores.snapshotting
      )
      onMessageReceive = { (nodeAddress: NodeAddress, swimMessage: SwimMessage) =>
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
          _ <- runtimeServer.sendAndForget(nodeAddress, swimMessage.copy(response = true, payload = result))
        } yield ()
      }
      _ <- runtimeServer.registerListener(typeName, onMessageReceive)

    } yield KeyAlgebraSender.keyToAlgebra[Key, Algebra, State, Event, Reject](readSubscription(clock, runtimeServer, stores.committableJournalStore))(
      { (key, payload) =>
        val keyString = implicitly[StringEncoder[Key]].encode(key)

        runtimeServer.send(keyString, typeName, payload)
      },
      eventSourcedBehaviour.errorHandler
    )
  }

}

trait RuntimeServer {

  def readSideProcessing: ReadSideProcessing

  def registerListener(entityType: String, messageReceivedForMe: (NodeAddress, SwimMessage) => ZIO[Any, Throwable, Unit]): UIO[Unit]

  def sendAndForget(nodeAddress: NodeAddress, swimMessage: SwimMessage): Task[Unit]

  def send(key: String, entityType: String, payload: Chunk[Byte]): Task[Chunk[Byte]]

}

case class SwimMessage(invocationId: UUID, response: Boolean, key: String, entityType: String, payload: Chunk[Byte])

object SwimRuntimeServer {
  val messageTimeout: Duration = 5.seconds
  val live: ZLayer[Swim[SwimMessage] with Clock, Throwable, Has[RuntimeServer]] = (for {
    swim                       <- ZManaged.service[Memberlist.Service[SwimMessage]]
    clock                      <- ZManaged.service[Clock.Service]
    hub                        <- ZHub.bounded[(NodeAddress, SwimMessage)](128).toManaged_
    _                          <- memberlist.receive[SwimMessage].run(Sink.fromHub(hub)).toManaged_
    listeners                  <- Ref.make[Map[String, (NodeAddress, SwimMessage) => Task[Unit]]](Map.empty).toManaged_
    receiveMessageSubscription <- hub.subscribe
    _ <- ZStream
      .fromQueue(receiveMessageSubscription)
      .mapMPar(128) { case (nodeAddress, swimMessage) =>
        for {
          listener <- listeners.get
          currentListener = listener.get(swimMessage.entityType)
          _ <- currentListener.get.apply(nodeAddress, swimMessage)
        } yield ()
      }
      .runDrain
      .toManaged_
      .fork
  } yield new RuntimeServer {

    val readSideProcessing: ReadSideProcessing = new SwimReadSideProcessing(swim, clock)
    // subscribe to hub that receive message, has a cache of combinators by entity and key and has an eviction strategy

    override def send(key: String, entityType: String, payload: Chunk[Byte]): Task[Chunk[Byte]] = {
      (for {
        dequeue     <- hub.subscribe
        activeNodes <- memberlist.nodes[SwimMessage].toManaged_
        nodeToUse = ShardLogic.getShardNode(key, activeNodes.toList)
        uuid = UUID.randomUUID()
        _ <- memberlist
          .send[SwimMessage](
            SwimMessage(invocationId = uuid, response = false, key = key, entityType = entityType, payload = payload),
            nodeToUse
          )
          .toManaged_
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

    override def sendAndForget(nodeAddress: NodeAddress, swimMessage: SwimMessage): Task[Unit] =
      memberlist.send(swimMessage, nodeAddress).provideLayer(ZLayer.succeed(swim))

    override def registerListener(entityType: String, messageReceivedForMe: (NodeAddress, SwimMessage) => ZIO[Any, Throwable, Unit]): UIO[Unit] =
      listeners.update { old =>
        old + (entityType -> messageReceivedForMe)
      }
  }).toLayer
}

object ShardLogic {

  def getShardNode(key: String, nodes: List[NodeAddress]): NodeAddress = {
    val numberOfShards = nodes.size
    val nodeIndex: Int = scala.math.abs(key.hashCode) % numberOfShards
    nodes(nodeIndex)
  }
}

class SwimReadSideProcessing(swim: Memberlist.Service[SwimMessage], clock: Clock.Service) extends ReadSideProcessing {
  override def start(name: String, processes: List[ReadSideProcess]): Task[KillSwitch] = {
    def checkNodesAndRun: ZIO[Swim[SwimMessage], Throwable, Task[Unit]] = for {
      nodes <- memberlist.nodes[SwimMessage]
      shardNode = ShardLogic.getShardNode(name, nodes.toList)
      localNode        <- memberlist.localMember[SwimMessage] if localNode == shardNode
      runningProcesses <- ZIO.collectAll(processes.map(_.run))
      kill = ZIO.foreach(runningProcesses)(_.shutdown).unit
    } yield kill
    // every x seconds and checking events continue to check active nodes and if I am the node i, start the process
    (for {
      state <- Ref.make[Task[Unit]](Task.unit)
      kill  <- checkNodesAndRun
      _     <- state.set(kill)
      _ <- memberlist
        .events[SwimMessage]
        .debounce(500.millis)
        .foreach { _ =>
          for {
            killel  <- state.get
            _       <- killel
            newKill <- checkNodesAndRun
            _       <- state.set(newKill)
          } yield ()
        }
        .fork
    } yield KillSwitch(Task.fromFunctionM(_ => state.get.flatten))).provideLayer(ZLayer.succeed(swim) ++ ZLayer.succeed(clock))
  }

}
