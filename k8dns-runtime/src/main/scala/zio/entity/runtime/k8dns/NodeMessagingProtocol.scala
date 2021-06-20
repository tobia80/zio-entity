package zio.entity.runtime.k8dns

import com.google.protobuf.ByteString
import io.grpc.protobuf.services.ProtoReflectionService
import io.grpc.{ManagedChannelBuilder, ServerBuilder, Status}
import scalapb.zio_grpc.{ManagedServer, ZManagedChannel}
import zio.ZManaged.Finalizer
import zio.entity.runtime.k8dns.SwimRuntimeServer.RichNodeAddress
import zio.entity.runtime.k8dns.protocol.ZioProtocol.ProtoStreamerClient.Service
import zio.entity.runtime.k8dns.protocol.ZioProtocol.{ProtoStreamer, ProtoStreamerClient}
import zio.entity.runtime.k8dns.protocol.{InternalMessage, ZioProtocol}
import zio.logging.{Logger, Logging}
import zio.memberlist.NodeAddress
import zio.stream.ZStream
import zio.{Chunk, Exit, Has, IO, Managed, Promise, Ref, RefM, Task, ZIO, ZLayer, ZManaged}

import java.time.Instant
import java.util.UUID

// it could use bidirectional streaming in grpc dealing with messages
trait NodeMessagingProtocol {

  def ask(nodeAddress: NodeAddress, payload: Message): Task[Message]

  def answer(nodeAddress: NodeAddress, data: Message): ZIO[Any, Throwable, Unit]

  val receive: ZStream[Any, Throwable, (NodeAddress, Message)]

  def updateConnections(nodes: Set[NodeAddress]): Task[Unit]
}

object GrpcNodeMessagingProtocol {
  case class InvocationKey(id: UUID)
  // connect by node
  val live: ZLayer[Logging, Throwable, Has[NodeMessagingProtocol]] = (for {
    logging     <- ZManaged.service[Logger[String]]
    clients     <- ZManaged.fromEffect(RefM.make[Map[NodeAddress, (ProtoStreamerClient.ZService[Any, Any], ZManaged.Finalizer)]](Map.empty))
    invocations <- ZManaged.fromEffect(Ref.make[Map[InvocationKey, Promise[Throwable, (NodeAddress, Message)]]](Map.empty))
    queue       <- ZManaged.fromEffect(zio.Queue.unbounded[(NodeAddress, Message)])
    scope       <- ZManaged.scope
    // every time connect is called, means a new connection from the client, add to map, merge the input streams, put output streams in the map, when a node address goes down, remove from the map
    runtimeStreamer: ProtoStreamer = new ZioProtocol.ProtoStreamer {
      override def sendMessage(request: InternalMessage): ZIO[Any, Status, InternalMessage] = {
        //generate correlation id, set the map and send to stream
        val uuid = UUID.fromString(request.correlationId)
        (for {
          promise <- Promise.make[Throwable, (NodeAddress, Message)]
          (nodeAddress, message) = InternalMessageConverter.toMessage(request)
          _   <- invocations.update { oldMap => oldMap + (InvocationKey(uuid) -> promise) }
          _   <- queue.offer((nodeAddress, message))
          res <- promise.await
        } yield InternalMessageConverter.toInternalMessage(res._1, res._2)).mapError(_ => Status.FAILED_PRECONDITION)
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
      val internalMessage = InternalMessageConverter.toInternalMessage(nodeAddress, message)
      for {
        clientMap <- clients.get
        response <- clientMap.get(nodeAddress).fold[Task[Message]](IO.fail(new Throwable("Cannot find client"))) { case (client, _) =>
          client
            .sendMessage(internalMessage)
            .map { resp =>
              val (_, response) = InternalMessageConverter.toMessage(resp)
              response
            }
            .mapError(error => new Throwable(error.toString))
        }
      } yield response
    }

    // server
    override def answer(nodeAddress: NodeAddress, data: Message): ZIO[Any, Throwable, Unit] = {
      // search the invocation key, resolve the promise, delete from map
      for {
        promise <- invocations.modify { internalMap =>
          val key = InvocationKey(data.correlationId)
          val promise = internalMap(key)
          (promise, internalMap - key)
        }
        _ <- promise.succeed(nodeAddress -> data)
      } yield ()
    }

    // server receiving messages from connect
    // stream from all the connect requests
    override val receive: ZStream[Any, Throwable, (NodeAddress, Message)] = ZStream.fromQueue(queue)

    // check map and remove callback
    override def updateConnections(nodes: Set[NodeAddress]): Task[Unit] = {
      //if cached client, they are created/ rebuilt, invocations can be removed from the map or we can have a persisted dead letter messages

      clients.update { oldMap =>
        // remove elements not present anymore and add elements
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
