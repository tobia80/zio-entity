package zio.entity.core

import zio.{Has, Ref, Tag, UIO, ZIO, ZLayer}

object LocalRuntime {

  // build a key => algebra transformed with key
  def buildLocalEntity[Algebra, Key: Tag, Event: Tag, State: Tag, Reject: Tag](
    eventSourcedBehaviour: EventSourcedBehaviour[Algebra, State, Event, Reject],
    algebraCombinatorConfig: AlgebraCombinatorConfig[Key, State, Event], //default combinator that tracks events and states
    combinatorMap: Ref[Map[Key, UIO[Combinators[State, Event, Reject]]]]
  ): UIO[Entity[Key, Algebra, State, Event, Reject]] = ZIO.runtime.map { runtime =>
    val fn: Key => (Algebra, Combinators[State, Event, Reject]) = { key: Key =>
      val errorHandler: Throwable => Reject = eventSourcedBehaviour.errorHandler
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
    new Entity[Key, Algebra, State, Event, Reject] {

      override def apply[R <: Has[_], Result](key: Key)(f: Algebra => ZIO[R, Reject, Result])(implicit
        ev1: Has[Combinators[State, Event, Reject]] <:< R
      ): ZIO[Any, Reject, Result] = {
        val (algebra, combinators) = fn(key)
        f(algebra).provideLayer(ZLayer.succeed(combinators))
      }
    }
  }
}
