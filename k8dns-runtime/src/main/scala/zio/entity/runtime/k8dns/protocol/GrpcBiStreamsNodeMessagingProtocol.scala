package zio.entity.runtime.k8dns.protocol

import com.google.protobuf.ByteString
import io.grpc.protobuf.services.ProtoReflectionService
import io.grpc.{ManagedChannelBuilder, ServerBuilder, Status}
import scalapb.zio_grpc.{ManagedServer, ZManagedChannel}
import zio.ZManaged.Finalizer
import zio.entity.runtime.k8dns.Message
import zio.entity.runtime.k8dns.SwimRuntimeServer.RichNodeAddress
import zio.entity.runtime.k8dns.protocol.ZioProtocol.ProtoStreamerClient.Service
import zio.entity.runtime.k8dns.protocol.ZioProtocol.{BidiProtoStreamer, BidiProtoStreamerClient, ProtoStreamer, ProtoStreamerClient}
import zio.logging.{Logger, Logging}
import zio.memberlist.{send, NodeAddress}
import zio.stream.{ZSink, ZStream, ZTransducer}
import zio.{stream, Chunk, Exit, Has, IO, Managed, Promise, Queue, RefM, Task, ZHub, ZIO, ZLayer, ZManaged}

import java.time.Instant
import java.util.UUID

// TODO: instead of correlation id and invocation map, just offer in the queue a promise to resolve
object GrpcBiStreamsNodeMessagingProtocol {
  case class InvocationKey(id: UUID)
  // connect by node
  val live: ZLayer[Logging, Throwable, Has[NodeMessagingProtocol]] = (for {
    logging        <- ZManaged.service[Logger[String]]
    outboundQueues <- ZManaged.fromEffect(RefM.make[Map[NodeAddress, (zio.Queue[StreamingInternalMessage], ZManaged.Finalizer)]](Map.empty))
    inbound        <- ZManaged.fromEffect(ZHub.unbounded[StreamingInternalMessage])
    scope          <- ZManaged.scope
    // every time connect is called, means a new connection from the client, add to map, merge the input streams, put output streams in the map, when a node address goes down, remove from the map
    runtimeStreamer: BidiProtoStreamer = new ZioProtocol.BidiProtoStreamer {
      override def channels(request: stream.Stream[Status, StreamingInternalMessage]): ZStream[Any, Status, StreamingInternalMessage] = {
        // put all the request in received queue and put as output the stream coming from the client (we need the node address)
        // other requires to connect so this represents requests returning responses
        val managed: ZIO[Any, Status, ZStream[Any, Nothing, StreamingInternalMessage]] = (for {
          firstMessageAndStream <- request.peel(ZSink.head)
          (firstMessage, stream) = firstMessageAndStream
          message     <- IO.fromOption(firstMessage).toManaged_
          _           <- stream.foreach { message => inbound.publish(message) }.toManaged_
          outboundMap <- outboundQueues.get.toManaged_
          from = StreamingInternalMessageConverter.toMessage(message)._1
          out <- IO.fromOption(outboundMap.get(from)).toManaged_
        } yield ZStream.fromQueue(out._1)).useNow.mapError(_ => Status.UNKNOWN)

        ZStream.fromEffect(managed).flatten
      }
    }
    _ <- ZManaged.fromEffect(
      for {
        _ <- zio.logging.log.info("Starting server on port 9010")
        _ <- ManagedServer.fromService(ServerBuilder.forPort(9010).addService(ProtoReflectionService.newInstance()), runtimeStreamer).useForever.fork
        _ <- zio.logging.log.info("Started server on port 9010")
      } yield ()
    )
  } yield new NodeMessagingProtocol {

    // TODO: start the server here
    // client call, find channel and use it
    override def ask(nodeAddress: NodeAddress, message: Message): Task[Message] = {
      val internalMessage = StreamingInternalMessageConverter.toInternalMessage(nodeAddress = nodeAddress, message = message, response = false)
      (for {
        // subscribe with hub to receive and filter by correlationId on response queue
        outboundQueuesMap <- outboundQueues.get.toManaged_
        queue             <- inbound.subscribe
        responseFiber     <- ZStream.fromQueue(queue).filter(el => el.response && el.correlationId == message.correlationId.toString).runHead.fork.toManaged_
        _ <- outboundQueuesMap
          .get(nodeAddress)
          .fold[Task[Boolean]](Task.fail(new Throwable("Cannot find client"))) { case (outboundQueue, _) =>
            outboundQueue.offer(internalMessage)
          }
          .toManaged_
        response <- responseFiber.join.toManaged_
        resp     <- IO.fromOption(response).mapError(_ => new Throwable("")).toManaged_
      } yield StreamingInternalMessageConverter.toMessage(resp)._2).useNow
    }

    // server receiving messages from connect
    // stream from all the connect requests
    override val receive: ZStream[Any, Throwable, (NodeAddress, Message, Message => Task[Unit])] = {
      val hub = inbound.filterInput[StreamingInternalMessage](_.response == false).mapM { element =>
        val (address, message, _) = StreamingInternalMessageConverter.toMessage(element)
        for {
          queues                    <- outboundQueues.get
          outboundQueueAndFinalizer <- IO.fromOption(queues.get(address)).mapError(_ => new Throwable(""))
          sendFn = (message: Message) => outboundQueueAndFinalizer._1.offer(StreamingInternalMessageConverter.toInternalMessage(address, message, true)).unit
        } yield (address, message, sendFn)
      }
      ZStream.fromHub(hub)
    }

    // check map and remove callback
    override def updateConnections(nodes: Set[NodeAddress]): Task[Unit] = {
      //if cached client, they are created/ rebuilt, invocations can be removed from the map or we can have a persisted dead letter messages
// build channels and call them
      outboundQueues.update { oldMap =>
        // remove elements not present anymore and add elements
        val elementsToRemove: Map[NodeAddress, (zio.Queue[StreamingInternalMessage], ZManaged.Finalizer)] = oldMap.filterNot { el =>
          nodes(el._1)
        }
        val closing = ZIO.foreach(elementsToRemove.toSet) { element =>
          val (queue, release) = element._2
          queue.shutdown *> release(Exit.unit)
        }

        val elementToAddsWithClients: ZIO[Logging, Throwable, Set[(NodeAddress, (zio.Queue[StreamingInternalMessage], Finalizer))]] =
          ZIO.foreach(
            nodes
              .filterNot { node =>
                oldMap.contains(node)
              }
          ) { node =>
            val address = node.toIpString
            val clientManaged: Managed[Throwable, BidiProtoStreamerClient.ZService[Any, Any]] =
              BidiProtoStreamerClient.managed(ZManagedChannel(ManagedChannelBuilder.forAddress(address, 9010).usePlaintext()))
            logging.info(s"Creating client for address $address:9010") *>
            scope(clientManaged).flatMap { case (finaliser, service) =>
              for {
                outboundQueue <- zio.Queue.unbounded[StreamingInternalMessage]
                // only creates clients, do not call channels
                _ <- service
                  .channels(ZStream.fromQueue(outboundQueue))
                  .foreach { element =>
                    inbound.publish(element)
                  }
                  .mapError(error => new Throwable(error.toString))
              } yield (node -> (outboundQueue, finaliser))
            }
          }
        for {
          _             <- closing
          elementsToAdd <- elementToAddsWithClients.provideLayer(ZLayer.succeed(logging))
        } yield ((oldMap -- elementsToRemove.keySet) ++ elementsToAdd)
      }
    }
  }).toLayer

}

object StreamingInternalMessageConverter {
  def toInternalMessage(nodeAddress: NodeAddress, message: Message, response: Boolean): StreamingInternalMessage = {
    StreamingInternalMessage(
      ByteString.copyFrom(nodeAddress.ip),
      nodeAddress.port,
      message.key,
      message.entityType,
      message.correlationId.toString,
      response,
      ByteString.copyFrom(message.payload.toArray)
    )
  }
  def toMessage(internalMessage: StreamingInternalMessage): (NodeAddress, Message, Boolean) = {
    val nodeAddress = NodeAddress(internalMessage.ip.toByteArray, internalMessage.port)
    val message = Message(
      internalMessage.key,
      internalMessage.entityType,
      UUID.fromString(internalMessage.correlationId),
      Chunk.fromArray(internalMessage.payload.toByteArray),
      Instant.ofEpochMilli(internalMessage.createdAt)
    )
    (nodeAddress, message, internalMessage.response)
  }
}
