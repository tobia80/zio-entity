package zio.entity.core

import zio.clock.Clock
import zio.entity.core.journal.CommittableJournalQuery
import zio.entity.data.{CommandResult, EntityProtocol, Tagging}
import zio.entity.readside.{KillSwitch, ReadSideParams, ReadSideProcessing, ReadSideProcessor}
import zio.stream.ZStream
import zio.{Chunk, Has, Ref, Tag, UIO, ZIO}

object LocalRuntimeWithProtocol {

  def entityLive[Key: StringDecoder: StringEncoder: Tag, Algebra, State: Tag, Event: Tag, Reject: Tag](
    name: String,
    tagging: Tagging[Key],
    eventSourcedBehaviour: EventSourcedBehaviour[Algebra, State, Event, Reject]
  )(implicit
    protocol: EntityProtocol[Algebra, Reject]
  ): ZIO[Clock with Has[Stores[Key, Event, State]], Throwable, Entity[Key, Algebra, State, Event, Reject]] = {
    for {
      clock          <- ZIO.service[Clock.Service]
      stores         <- ZIO.service[Stores[Key, Event, State]]
      combinatorsMap <- Ref.make[Map[Key, UIO[Combinators[State, Event, Reject]]]](Map.empty)
      combinators = AlgebraCombinatorConfig[Key, State, Event](
        stores.offsetStore,
        tagging,
        stores.journalStore,
        stores.snapshotting
      )
      algebra <- buildLocalEntity(eventSourcedBehaviour, combinators, combinatorsMap, clock, stores.committableJournalStore)
    } yield algebra
  }

  def buildLocalEntity[Algebra, Key: Tag, Event: Tag, State: Tag, Reject: Tag](
    eventSourcedBehaviour: EventSourcedBehaviour[Algebra, State, Event, Reject],
    algebraCombinatorConfig: AlgebraCombinatorConfig[Key, State, Event], //default combinator that tracks events and states
    combinatorMap: Ref[Map[Key, UIO[Combinators[State, Event, Reject]]]],
    clock: Clock.Service,
    journalQuery: CommittableJournalQuery[Long, Key, Event]
  )(implicit protocol: EntityProtocol[Algebra, Reject]): UIO[Entity[Key, Algebra, State, Event, Reject]] = {
    val errorHandler: Throwable => Reject = eventSourcedBehaviour.errorHandler
    val subscription: (ReadSideParams[Key, Event, Reject], Throwable => Reject) => ZStream[Any, Reject, KillSwitch] =
      (readSideParams, errorHandler) =>
        ReadSideProcessor.readSideStream[Key, Event, Long, Reject](readSideParams, errorHandler, clock, ReadSideProcessing.memoryInner, journalQuery)
    UIO.succeed(
      KeyAlgebraSender.keyToAlgebra[Key, Algebra, State, Event, Reject](subscription)(
        { (key: Key, bytes: Chunk[Byte]) =>
          val algebraCombinators: UIO[Combinators[State, Event, Reject]] = for {
            cache <- combinatorMap.get
            combinatorRetrieved <- cache.get(key) match {
              case Some(combinator) =>
                combinator
              case None =>
                KeyedAlgebraCombinators
                  .fromParams[Key, State, Event, Reject](key, eventSourcedBehaviour.eventHandler, eventSourcedBehaviour.errorHandler, algebraCombinatorConfig)
                  .flatMap { combinator =>
                    val uioCombinator = UIO.succeed(combinator)
                    val newMap = cache + (key -> uioCombinator)
                    combinatorMap.set(newMap) *> uioCombinator
                  }
            }
          } yield combinatorRetrieved
          algebraCombinators.flatMap { combinator =>
            protocol
              .server(eventSourcedBehaviour.algebra(combinator), errorHandler)
              .call(bytes)
              .map(CommandResult)
          }
        },
        errorHandler
      )
    )
  }

}
