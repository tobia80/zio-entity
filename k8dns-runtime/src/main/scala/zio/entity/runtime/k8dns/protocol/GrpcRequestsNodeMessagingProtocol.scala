package zio.entity.runtime.k8dns.protocol

import com.google.protobuf.ByteString
import io.grpc.protobuf.services.ProtoReflectionService
import io.grpc.{ManagedChannelBuilder, ServerBuilder, Status}
import scalapb.zio_grpc.{ManagedServer, ZManagedChannel}
import zio.ZManaged.Finalizer
import zio.entity.runtime.k8dns.Message
import zio.entity.runtime.k8dns.SwimRuntimeServer.RichNodeAddress
import zio.entity.runtime.k8dns.protocol.ZioProtocol.ProtoStreamerClient.Service
import zio.entity.runtime.k8dns.protocol.ZioProtocol.{ProtoStreamer, ProtoStreamerClient}
import zio.logging.{Logger, Logging}
import zio.memberlist.NodeAddress
import zio.stream.ZStream
import zio.{Chunk, Exit, Has, IO, Managed, Promise, RefM, Task, ZHub, ZIO, ZLayer, ZManaged}

import java.time.Instant
import java.util.UUID

// TODO: instead of correlation id and invocation map, just offer in the queue a promise to resolve
object GrpcRequestsNodeMessagingProtocol {
  case class InvocationKey(id: UUID)
  // connect by node
  val live: ZLayer[Logging, Throwable, Has[NodeMessagingProtocol]] = (for {
    logging         <- ZManaged.service[Logger[String]]
    clients         <- ZManaged.fromEffect(RefM.make[Map[NodeAddress, (ProtoStreamerClient.ZService[Any, Any], ZManaged.Finalizer)]](Map.empty))
    inbound         <- ZManaged.fromEffect(zio.Queue.unbounded[(NodeAddress, Message, Message => Task[Unit])])
    inboundResponse <- ZManaged.fromEffect(ZHub.unbounded[(NodeAddress, Message)])
    outbound        <- ZHub.bounded[(NodeAddress, Message)](50).toManaged_
    _ <- ZStream
      .fromHub(outbound)
      .foreach { case (address, message) =>
        for {
          clientMap <- clients.get
          addressAndResponse <- clientMap.get(address).fold[Task[(NodeAddress, Message)]](IO.fail(new Throwable("Cannot find client"))) { case (client, _) =>
            // send to queue
            val internalMessage = InternalMessageConverter.toInternalMessage(address, message)
            client
              .sendMessage(internalMessage)
              .map { resp =>
                InternalMessageConverter.toMessage(resp)
              }
              .mapError(error => new Throwable(error.toString))
          }
          _ <- inboundResponse.publish(addressAndResponse)
        } yield ()
      }
      .fork
      .toManaged_

    scope <- ZManaged.scope
    // every time connect is called, means a new connection from the client, add to map, merge the input streams, put output streams in the map, when a node address goes down, remove from the map
    runtimeStreamer: ProtoStreamer = new ZioProtocol.ProtoStreamer {
      override def sendMessage(request: InternalMessage): ZIO[Any, Status, InternalMessage] = {
        (for {
          promise <- Promise.make[Throwable, Message]
          sendFn = (message: Message) => promise.succeed(message).unit
          (nodeAddress, message) = InternalMessageConverter.toMessage(request)
          _   <- inbound.offer((nodeAddress, message, sendFn))
          res <- promise.await
        } yield InternalMessageConverter.toInternalMessage(nodeAddress, res)).mapError(_ => Status.FAILED_PRECONDITION)
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
      (for {
        queue    <- inboundResponse.subscribe
        fiber    <- ZStream.fromQueue(queue.filterOutput(_._2.correlationId == message.correlationId)).take(1).runHead.fork.toManaged_
        _        <- outbound.publish(nodeAddress, message).toManaged_
        resMaybe <- fiber.join.toManaged_
        res      <- IO.fromOption(resMaybe).mapError(_ => new Throwable("No message returned")).toManaged_
      } yield res._2).useNow
    }

    // server receiving messages from connect
    // stream from all the connect requests
    override val receive: ZStream[Any, Throwable, (NodeAddress, Message, Message => Task[Unit])] = ZStream.fromQueue(inbound)

    override def updateConnections(nodes: Set[NodeAddress]): Task[Unit] = {
      clients.update { oldMap =>
        val elementsToRemove: Map[NodeAddress, (Service, ZManaged.Finalizer)] = oldMap.filterNot { el =>
          nodes(el._1)
        }
        val closing = ZIO.foreach(elementsToRemove.toSet) { element =>
          val (_, release) = element._2
          release(Exit.unit)
        }

        val elementToAddsWithClients: ZIO[Logging, Throwable, Set[(NodeAddress, (ProtoStreamerClient.ZService[Any, Any], Finalizer))]] =
          ZIO.foreach(
            nodes
              .filterNot { node =>
                oldMap.contains(node)
              }
          ) { node =>
            val address = node.toIpString
            val clientManaged: Managed[Throwable, ProtoStreamerClient.ZService[Any, Any]] =
              ProtoStreamerClient.managed(ZManagedChannel(ManagedChannelBuilder.forAddress(address, 9010).usePlaintext()))
            logging.info(s"Creating client for address $address:9010") *>
            scope(clientManaged).map { case (finaliser, service) =>
              node -> (service, finaliser)
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

object InternalMessageConverter {
  def toInternalMessage(nodeAddress: NodeAddress, message: Message): InternalMessage = {
    InternalMessage(
      ByteString.copyFrom(nodeAddress.ip),
      nodeAddress.port,
      message.key,
      message.entityType,
      message.correlationId.toString,
      ByteString.copyFrom(message.payload.toArray)
    )
  }
  def toMessage(internalMessage: InternalMessage): (NodeAddress, Message) = {
    val nodeAddress = NodeAddress(internalMessage.ip.toByteArray, internalMessage.port)
    val message = Message(
      internalMessage.key,
      internalMessage.entityType,
      UUID.fromString(internalMessage.correlationId),
      Chunk.fromArray(internalMessage.payload.toByteArray),
      Instant.ofEpochMilli(internalMessage.createdAt)
    )
    nodeAddress -> message
  }
}
