package zio.entity.core

import zio.{Has, Ref, Tag, UIO, ZIO, ZLayer}

object LocalRuntime extends AbstractRuntime {

  type Entity[Algebra, Key, State, Event, Reject] = Key => (Algebra, Combinators[State, Event, Reject])

  def call[R <: Has[_], Algebra, Key, Event: Tag, State: Tag, Reject: Tag, Result](key: Key, processor: Entity[Algebra, Key, State, Event, Reject])(
    fn: Algebra => ZIO[R with Has[Combinators[State, Event, Reject]], Reject, Result]
  ): ZIO[R, Reject, Result] = {
    val (algebra, combinators) = processor(key)
    fn(algebra).provideSomeLayer[R](ZLayer.succeed(combinators))
  }
  // build a key => algebra transformed with key
  def buildLocalEntity[Algebra, Key: Tag, Event: Tag, State: Tag, Reject: Tag](
    eventSourcedBehaviour: EventSourcedBehaviour[Algebra, State, Event, Reject],
    algebraCombinatorConfig: AlgebraCombinatorConfig[Key, State, Event], //default combinator that tracks events and states
    combinatorMap: Ref[Map[Key, UIO[Combinators[State, Event, Reject]]]]
  ): UIO[Entity[Algebra, Key, State, Event, Reject]] = ZIO.runtime.map { runtime => key: Key =>
    val errorHandler: Throwable => Reject = eventSourcedBehaviour.errorHandler
    // TODO in order to have an identity protocol, we need
    val result: UIO[(Algebra, Combinators[State, Event, Reject])] = for {
      cache <- combinatorMap.get
      combinator <- cache.getOrElse(
        key, {
          val newComb =
            KeyedAlgebraCombinators.fromParams[Key, State, Event, Reject](key, eventSourcedBehaviour.eventHandler, errorHandler, algebraCombinatorConfig)
          val newMap = cache + (key -> newComb)
          combinatorMap.set(newMap) *> newComb
        }
      )
    } yield eventSourcedBehaviour.algebra -> combinator
    runtime.unsafeRun(result)
  }
}
