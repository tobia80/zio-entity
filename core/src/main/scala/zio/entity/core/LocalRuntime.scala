package zio.entity.core

import zio.clock.Clock
import zio.entity.core.journal.CommittableJournalQuery
import zio.entity.readside.{KillSwitch, ReadSideParams, ReadSideProcessing, ReadSideProcessor}
import zio.stream.ZStream
import zio.{Has, IO, Ref, Tag, UIO, ZIO, ZLayer}

object LocalRuntime {

  // build a key => algebra transformed with key
  def buildLocalEntity[Algebra, Key: Tag, Event: Tag, State: Tag, Reject: Tag](
    eventSourcedBehaviour: EventSourcedBehaviour[Algebra, State, Event, Reject],
    algebraCombinatorConfig: AlgebraCombinatorConfig[Key, State, Event], //default combinator that tracks events and states
    committableJournalQuery: CommittableJournalQuery[Long, Key, Event],
    combinatorMap: Ref[Map[Key, UIO[Combinators[State, Event, Reject]]]],
    clock: Clock.Service
  ): UIO[Entity[Key, Algebra, State, Event, Reject]] = ZIO.runtime.map { runtime =>
    val fn: Key => Algebra = { key: Key =>
      val errorHandler: Throwable => Reject = eventSourcedBehaviour.errorHandler
      val result: UIO[Algebra] = for {
        cache <- combinatorMap.get
        combinator <- cache.getOrElse(
          key, {
            val newComb =
              KeyedAlgebraCombinators.fromParams[Key, State, Event, Reject](key, eventSourcedBehaviour.eventHandler, errorHandler, algebraCombinatorConfig)
            val newMap = cache + (key -> newComb)
            combinatorMap.set(newMap) *> newComb
          }
        )
      } yield eventSourcedBehaviour.algebra(combinator)
      runtime.unsafeRun(result)
    }
    new Entity[Key, Algebra, State, Event, Reject] {

      override def apply(key: Key): Algebra = {
        fn(key)
      }

      override def readSideStream(
        readSideParams: ReadSideParams[Key, Event, Reject],
        errorHandler: Throwable => Reject
      ): ZStream[Any, Reject, KillSwitch] =
        ReadSideProcessor.readSideStream[Key, Event, Long, Reject](
          readSideParams,
          errorHandler,
          clock,
          ReadSideProcessing.memoryInner,
          committableJournalQuery
        )
    }
  }
}
