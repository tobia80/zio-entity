package zio.entity.core

import zio.{Ref, Tag, UIO, ZIO}

object LocalRuntime {

  type Entity[Key, Algebra, State, Event, Reject] = Key => (Algebra, Combinators[State, Event, Reject])

  // build a key => algebra transformed with key
  def buildLocalEntity[Algebra, Key: Tag, Event: Tag, State: Tag, Reject: Tag](
    eventSourcedBehaviour: EventSourcedBehaviour[Algebra, State, Event, Reject],
    algebraCombinatorConfig: AlgebraCombinatorConfig[Key, State, Event], //default combinator that tracks events and states
    combinatorMap: Ref[Map[Key, UIO[Combinators[State, Event, Reject]]]]
  ): UIO[Entity[Key, Algebra, State, Event, Reject]] = ZIO.runtime.map { runtime => key: Key =>
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
