package zio.entity.runtime.akka

import akka.actor.{Actor, ActorLogging, Props, ReceiveTimeout, Stash, Status}
import akka.cluster.sharding.ShardRegion
import izumi.reflect.Tag
import zio.entity.core._
import zio.entity.data.{CommandInvocation, CommandResult, EntityProtocol, Invocation}
import zio.{Chunk, Has, Runtime, UIO, ULayer, ZIO, ZLayer}

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import scala.concurrent.Future
import scala.util.{Failure, Success}

object ZioEntityActor {
  def props[Key: StringDecoder: Tag, Algebra, State: Tag, Event: Tag, Reject: Tag](
    eventSourcedBehaviour: EventSourcedBehaviour[Algebra, State, Event, Reject],
    algebraCombinatorConfig: AlgebraCombinatorConfig[Key, State, Event]
  )(implicit protocol: EntityProtocol[Algebra, Reject]): Props =
    Props(new ZioEntityActor[Key, Algebra, State, Event, Reject](eventSourcedBehaviour, algebraCombinatorConfig))
}

private class ZioEntityActor[Key: StringDecoder: Tag, Algebra, State: Tag, Event: Tag, Reject: Tag](
  eventSourcedBehaviour: EventSourcedBehaviour[Algebra, State, Event, Reject],
  algebraCombinatorConfig: AlgebraCombinatorConfig[Key, State, Event]
)(implicit protocol: EntityProtocol[Algebra, Reject])
    extends Actor
    with Stash
    with ActorLogging {

  private val keyString: String =
    URLDecoder.decode(self.path.name, StandardCharsets.UTF_8.name())

  private val key: Key = StringDecoder[Key]
    .decode(keyString)
    .getOrElse {
      val error = s"Failed to decode entity id from [$keyString]"
      log.error(error)
      throw new IllegalArgumentException(error)
    }

  private val algebraCombinatorsWithKeyResolved: UIO[Combinators[State, Event, Reject]] =
    KeyedAlgebraCombinators
      .fromParams[Key, State, Event, Reject](key, eventSourcedBehaviour.eventHandler, eventSourcedBehaviour.errorHandler, algebraCombinatorConfig)

  //TODO manage stashed messages
  override def receive: Receive = onActions

  private val invocation: UIO[Invocation] = algebraCombinatorsWithKeyResolved.map { combinator =>
    protocol.server(eventSourcedBehaviour.algebra(combinator), eventSourcedBehaviour.errorHandler)
  }
  private val runtime = Runtime.unsafeFromLayer(invocation.toLayer)
  // here key is available, so at this level we can store the state of the algebra
  private def onActions: Receive = {
    case CommandInvocation(bytes) =>
      val resultToSendBack: Future[CommandResult] = runtime
        .unsafeRunToFuture(for {
          invoc <- ZIO.service[Invocation]
          result <- invoc
            .call(bytes)
            .mapError { reject =>
              log.error("Failed to decode invocation", reject)
              sender() ! Status.Failure(reject)
              reject
            }
        } yield result)
        .map(replyBytes => CommandResult(replyBytes))(context.dispatcher)
      val sendingActor = sender()
      resultToSendBack.onComplete {
        case Success(r) =>
          sendingActor ! r
        case Failure(f) =>
          sendingActor ! Status.Failure(f)
      }(context.dispatcher)

    case ReceiveTimeout =>
      passivate()
    case Stop =>
      context.stop(self)
  }

  private def passivate(): Unit = {
    log.debug("Passivating...")
    context.parent ! ShardRegion.Passivate(Stop)
  }

}

case object Stop
