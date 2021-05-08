package zio.entity.core

import scodec.bits.BitVector
import zio.entity.core.journal.EventJournal
import zio.entity.core.snapshot.{KeyValueStore, MemoryKeyValueStore, Snapshotting}
import zio.entity.data.{CommandResult, EntityProtocol, Tagging, Versioned}
import zio.{Has, Ref, Tag, UIO, ZIO}

object LocalRuntimeWithProtocol {

  def entityLive[Key: StringDecoder: StringEncoder: Tag, Algebra, State: Tag, Event: Tag, Reject: Tag](
    tagging: Tagging[Key],
    eventSourcedBehaviour: EventSourcedBehaviour[Algebra, State, Event, Reject]
  )(implicit
    protocol: EntityProtocol[Algebra, State, Event, Reject]
  ): ZIO[Has[KeyValueStore[Key, Long]] with Has[KeyValueStore[Key, Versioned[State]]] with Has[
    EventJournal[Key, Event]
  ], Throwable, Entity[Key, Algebra, State, Event, Reject]] = {
    for {
      eventJournal          <- ZIO.service[EventJournal[Key, Event]]
      snapshotKeyValueStore <- ZIO.service[KeyValueStore[Key, Versioned[State]]]
      offsetKeyValueStore   <- ZIO.service[KeyValueStore[Key, Long]]
      combinatorsMap        <- Ref.make[Map[Key, UIO[Combinators[State, Event, Reject]]]](Map.empty)
      combinators = AlgebraCombinatorConfig.build[Key, State, Event](
        offsetKeyValueStore,
        tagging,
        eventJournal,
        Snapshotting.eachVersion(2, snapshotKeyValueStore)
      )
      algebra <- buildLocalEntity(eventSourcedBehaviour, combinators, combinatorsMap)
    } yield algebra
  }

  def memory[Key: StringDecoder: StringEncoder: Tag, Algebra, State: Tag, Event: Tag, Reject: Tag](
    tagging: Tagging[Key],
    eventSourcedBehaviour: EventSourcedBehaviour[Algebra, State, Event, Reject]
  )(implicit
    protocol: EntityProtocol[Algebra, State, Event, Reject]
  ): ZIO[Has[EventJournal[Key, Event]], Throwable, Entity[Key, Algebra, State, Event, Reject]] = {
    val memoryEventJournalOffsetStore = MemoryKeyValueStore.make[Key, Long].toLayer
    val snapshotKeyValueStore = MemoryKeyValueStore.make[Key, Versioned[State]].toLayer

    entityLive(tagging, eventSourcedBehaviour)
      .provideSomeLayer[Has[EventJournal[Key, Event]]](memoryEventJournalOffsetStore and snapshotKeyValueStore)
  }
  // build a key => algebra transformed with key
  def buildLocalEntity[Algebra, Key: Tag, Event: Tag, State: Tag, Reject: Tag](
    eventSourcedBehaviour: EventSourcedBehaviour[Algebra, State, Event, Reject],
    algebraCombinatorConfig: AlgebraCombinatorConfig[Key, State, Event], //default combinator that tracks events and states
    combinatorMap: Ref[Map[Key, UIO[Combinators[State, Event, Reject]]]]
  )(implicit protocol: EntityProtocol[Algebra, State, Event, Reject]): UIO[Entity[Key, Algebra, State, Event, Reject]] = {
    val errorHandler: Throwable => Reject = eventSourcedBehaviour.errorHandler
// TODO in order to have an identity protocol, we need
    UIO.succeed(
      KeyAlgebraSender.keyToAlgebra[Key, Algebra, State, Event, Reject](
        { (key: Key, bytes: BitVector) =>
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
// TODO: is it possible to remove serialization and deserialization?
          protocol
            .server(eventSourcedBehaviour.algebra, errorHandler)
            .call(bytes)
            .map(CommandResult)
            .provideLayer(algebraCombinators.toLayer)
        },
        errorHandler
      )
    )
  }

}
