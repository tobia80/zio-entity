package zio.entity.runtime.akka

import akka.actor.{Actor, ActorLogging, Props, ReceiveTimeout, Stash, Status}
import akka.cluster.sharding.ShardRegion
import izumi.reflect.Tag
import zio.entity.core.{AlgebraCombinatorConfig, Combinators, Fold, KeyedAlgebraCombinators, StringDecoder}
import zio.entity.data.{CommandInvocation, CommandResult, Invocation, StemProtocol}
import zio.{Has, Runtime, ULayer}

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object ZioEntityActor {
  def props[Key: StringDecoder: Tag, Algebra, State: Tag, Event: Tag, Reject: Tag](
    eventSourcedBehaviour: EventSourcedBehaviour[Algebra, State, Event, Reject],
    algebraCombinatorConfig: AlgebraCombinatorConfig[Key, State, Event]
  )(implicit protocol: StemProtocol[Algebra, State, Event, Reject]): Props =
    Props(new ZioEntityActor[Key, Algebra, State, Event, Reject](eventSourcedBehaviour, algebraCombinatorConfig))
}

private class ZioEntityActor[Key: StringDecoder: Tag, Algebra, State: Tag, Event: Tag, Reject: Tag](
  eventSourcedBehaviour: EventSourcedBehaviour[Algebra, State, Event, Reject],
  algebraCombinatorConfig: AlgebraCombinatorConfig[Key, State, Event]
)(implicit protocol: StemProtocol[Algebra, State, Event, Reject])
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

  private val algebraCombinatorsWithKeyResolved: ULayer[Has[Combinators[State, Event, Reject]]] =
    KeyedAlgebraCombinators
      .fromParams[Key, State, Event, Reject](key, eventSourcedBehaviour.eventHandler, eventSourcedBehaviour.errorHandler, algebraCombinatorConfig)
      .toLayer

  override def receive: Receive = {
    case Start =>
      unstashAll()
      context.become(onActions)
    case _ => stash()
  }

  // here key is available, so at this level we can store the state of the algebra
  private def onActions: Receive = {
    case CommandInvocation(bytes) =>
      //macro creates a map of functions of path -> Invocation
      val invocation: Invocation[State, Event, Reject] =
        protocol.server(eventSourcedBehaviour.algebra, eventSourcedBehaviour.errorHandler)

      sender() ! Runtime.default
        .unsafeRunToFuture(
          invocation
            .call(bytes)
            .provideLayer(algebraCombinatorsWithKeyResolved)
            .mapError { reject =>
              log.error("Failed to decode invocation", reject)
              sender() ! Status.Failure(reject)
              reject
            }
        )
        .map(replyBytes => CommandResult(replyBytes))(context.dispatcher)

    case ReceiveTimeout =>
      passivate()
    case Stop =>
      context.stop(self)
  }

  private def passivate(): Unit = {
    log.debug("Passivating...")
    context.parent ! ShardRegion.Passivate(Stop)
  }

  private case object Start

}

case object Stop

case class EventSourcedBehaviour[Algebra, State, Event, Reject](
  algebra: Algebra,
  eventHandler: Fold[State, Event],
  errorHandler: Throwable => Reject
)
