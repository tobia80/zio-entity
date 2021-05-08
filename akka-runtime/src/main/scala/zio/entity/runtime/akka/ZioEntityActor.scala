package zio.entity.runtime.akka

import akka.actor.{Actor, ActorLogging, Props, ReceiveTimeout, Stash, Status}
import akka.cluster.sharding.ShardRegion
import izumi.reflect.Tag
import zio.entity.core._
import zio.entity.data.{CommandInvocation, CommandResult, EntityProtocol, Invocation}
import zio.{Has, Runtime, UIO, ULayer, ZIO, ZLayer}

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import scala.concurrent.Future
import scala.util.{Failure, Success}

object ZioEntityActor {
  def props[Key: StringDecoder: Tag, Algebra, State: Tag, Event: Tag, Reject: Tag](
    eventSourcedBehaviour: EventSourcedBehaviour[Algebra, State, Event, Reject],
    algebraCombinatorConfig: AlgebraCombinatorConfig[Key, State, Event]
  )(implicit protocol: EntityProtocol[Algebra, State, Event, Reject]): Props =
    Props(new ZioEntityActor[Key, Algebra, State, Event, Reject](eventSourcedBehaviour, algebraCombinatorConfig))
}

private class ZioEntityActor[Key: StringDecoder: Tag, Algebra, State: Tag, Event: Tag, Reject: Tag](
  eventSourcedBehaviour: EventSourcedBehaviour[Algebra, State, Event, Reject],
  algebraCombinatorConfig: AlgebraCombinatorConfig[Key, State, Event]
)(implicit protocol: EntityProtocol[Algebra, State, Event, Reject])
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

  private val invocation: Invocation[State, Event, Reject] =
    protocol.server(eventSourcedBehaviour.algebra, eventSourcedBehaviour.errorHandler)

  private val runtime = Runtime.unsafeFromLayer(algebraCombinatorsWithKeyResolved.toLayer)
  // here key is available, so at this level we can store the state of the algebra
  private def onActions: Receive = {
    case CommandInvocation(bytes) =>
      //macro creates a map of functions of path -> Invocation

      val resultToSendBack: Future[CommandResult] = runtime
        .unsafeRunToFuture(
          (for {
            combinators <- ZIO.environment[Has[Combinators[State, Event, Reject]]]
            result <- invocation
              .call(bytes)
              .provide(combinators)
              .mapError { reject =>
                log.error("Failed to decode invocation", reject)
                sender() ! Status.Failure(reject)
                reject
              }
          } yield result)
        )
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

//  private case object Start

}

case object Stop
