package zio.entity.runtime.k8dns

import com.google.protobuf.ByteString
import io.grpc.{ManagedChannelBuilder, Status}
import scalapb.zio_grpc.ZManagedChannel
import zio.ZManaged.Finalizer
import zio.entity.runtime.k8dns.protocol.ZioProtocol.ProtoStreamerClient
import zio.entity.runtime.k8dns.protocol.ZioProtocol.ProtoStreamerClient.Service
import zio.entity.runtime.k8dns.protocol.{InternalMessage, ZioProtocol}
import zio.memberlist.NodeAddress
import zio.stream.ZStream
import zio.{Chunk, Exit, Has, IO, Managed, Promise, Ref, RefM, Task, UIO, ZIO, ZLayer, ZManaged}

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
  case class ClientAndCallback(sendBack: InternalMessage => Task[InternalMessage])
  case class InvocationKey(id: UUID)
  // connect by node
  val live: ZLayer[Any, Throwable, Has[NodeMessagingProtocol]] = (for {
    clients     <- ZManaged.fromEffect(RefM.make[Map[NodeAddress, (ProtoStreamerClient.ZService[Any, Any], ZManaged.Finalizer)]](Map.empty))
    invocations <- ZManaged.fromEffect(Ref.make[Map[InvocationKey, Promise[Throwable, (NodeAddress, Message)]]](Map.empty))
    queue       <- ZManaged.fromEffect(zio.Queue.unbounded[(NodeAddress, Message)])
    scope       <- ZManaged.scope
    // every time connect is called, means a new connection from the client, add to map, merge the input streams, put output streams in the map, when a node address goes down, remove from the map
  } yield new NodeMessagingProtocol {

    // TODO: start the server here
    private val runtimeStreamer = new ZioProtocol.ProtoStreamer {
      override def sendMessage(request: InternalMessage): ZIO[Any, Status, InternalMessage] = {
        //generate correlation id, set the map and send to stream
        (for {
          uuid    <- Task.effectTotal(UUID.randomUUID())
          promise <- Promise.make[Throwable, (NodeAddress, Message)]
          (nodeAddress, message) = InternalMessageConverter.toMessage(request)
          _   <- invocations.update { oldMap => oldMap + (InvocationKey(uuid) -> promise) }
          _   <- queue.offer((nodeAddress, message))
          res <- promise.await
        } yield InternalMessageConverter.toInternalMessage(res._1, res._2)).mapError(_ => Status.FAILED_PRECONDITION)
      }
    }
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
            .mapError(_ => new Throwable(""))
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

        val elementToAddsWithClients: ZIO[Any, Throwable, Set[(NodeAddress, (ProtoStreamerClient.ZService[Any, Any], Finalizer))]] =
          ZIO.foreach(
            nodes
              .filterNot { node =>
                oldMap.contains(node)
              }
          ) { node =>
            val clientManaged: Managed[Throwable, ProtoStreamerClient.ZService[Any, Any]] =
              ProtoStreamerClient.managed(ZManagedChannel(ManagedChannelBuilder.forAddress(node.ip.mkString("."), node.port)))
            scope(clientManaged).map { case (finaliser, service) =>
              node -> (service, finaliser)
            }
          }
        for {
          _             <- closing
          elementsToAdd <- elementToAddsWithClients
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
