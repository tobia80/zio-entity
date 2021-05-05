package zio.entity.test

import zio.blocking.Blocking
import zio.clock.Clock
import zio.duration.durationInt
import zio.entity.core._
import zio.entity.core.journal.{CommittableJournalQuery, MemoryEventJournal}
import zio.entity.core.snapshot.{MemoryKeyValueStore, Snapshotting}
import zio.entity.data.{EventTag, StemProtocol, Tagging, Versioned}
import zio.entity.readside
import zio.entity.readside.{KillSwitch, ReadSideParams, ReadSideProcessing, ReadSideProcessor}
import zio.entity.test.EntityProbe.KeyedProbeOperations
import zio.stream.ZStream
import zio.test.environment.TestClock
import zio.{Chunk, Fiber, Has, RIO, Ref, Tag, Task, UIO, URIO, ZIO, ZLayer}

object TestEntityRuntime extends AbstractRuntime {

  type Entity[Algebra, Key, State, Event, Reject] = Key => Algebra

  object TestReadSideProcessor {
    trait TestReadSideProcessor[Reject] {
      def triggerReadSideProcessing(triggerTimes: Int): URIO[TestClock, Unit]

      def triggerReadSideAndWaitFor(triggerTimes: Int, messagesToWaitFor: Int): ZIO[TestClock with Clock with Blocking, Reject, Unit]

    }

    def memory[Id: Tag, Event: Tag, Offset: Tag, Reject: Tag](
      errorHandler: Throwable => Reject
    ): ZLayer[Clock with Has[ReadSideParams[Id, Event, Reject]] with Has[CommittableJournalQuery[Offset, Id, Event]], Nothing, Has[
      ReadSideProcessor[Reject]
    ] with Has[
      TestReadSideProcessor[Reject]
    ]] = {
      (for {
        readSideParams          <- ZIO.service[ReadSideParams[Id, Event, Reject]]
        clock                   <- ZIO.service[Clock.Service]
        committableJournalQuery <- ZIO.service[CommittableJournalQuery[Offset, Id, Event]]
        stream: ZStream[Any, Reject, KillSwitch] = {
          ReadSideProcessor
            .readSideStream[Id, Event, Offset, Reject](readSideParams, errorHandler)
            .provideLayer(ZLayer.succeed(clock) ++ ReadSideProcessing.memory ++ ZLayer.succeed(committableJournalQuery))
        }
        el: ReadSideProcessor[Reject] with TestReadSideProcessor[Reject] = new readside.ReadSideProcessor[Reject] with TestReadSideProcessor[Reject] {
          override def readSideStream: ZStream[Any, Reject, KillSwitch] = stream

          override def triggerReadSideProcessing(triggerTimes: Int): URIO[TestClock, Unit] = TestClock.adjust((triggerTimes * 100).millis)

          def consume(n: Int): URIO[Clock with Blocking, Fiber.Runtime[Reject, Unit]] =
            stream.take(n).runDrain.fork

          override def triggerReadSideAndWaitFor(triggerTimes: Int, messagesToWaitFor: Int): ZIO[TestClock with Clock with Blocking, Reject, Unit] =
            for {
              fiber <- consume(messagesToWaitFor)
              _     <- triggerReadSideProcessing(triggerTimes)
              _     <- fiber.join
            } yield ()
        }
      } yield Has.allOf[ReadSideProcessor[Reject], TestReadSideProcessor[Reject]](el, el)).toLayerMany
    }
  }

  type TestEntity[Key, Algebra, State, Event, Reject] =
    Has[Key => Algebra] with Has[EntityProbe[Key, State, Event]]

  def call[R <: Has[_], Algebra, Key, Event: Tag, State: Tag, Reject: Tag, Result](key: Key, processor: Entity[Algebra, Key, State, Event, Reject])(
    fn: Algebra => ZIO[R with Has[Combinators[State, Event, Reject]], Reject, Result]
  ): ZIO[R, Reject, Result] = {
    val algebra = processor(key)
    fn(algebra).provideSomeLayer[R](Combinators.clientEmptyCombinator[State, Event, Reject])
  }

  def testEntity[Key: Tag, Algebra: Tag, State: Tag, Event: Tag, Reject: Tag](
    tagging: Tagging[Key],
    eventSourcedBehaviour: EventSourcedBehaviour[Algebra, State, Event, Reject]
  )(implicit
    protocol: StemProtocol[Algebra, State, Event, Reject]
  ): ZLayer[Has[MemoryEventJournal[Key, Event]] with Has[Snapshotting[Key, State]], Throwable, TestEntity[Key, Algebra, State, Event, Reject]] =
    EntityProbe.make[Key, State, Event](eventSourcedBehaviour.eventHandler).toLayer and ZLayer
      .service[MemoryEventJournal[Key, Event]] and ZLayer
      .service[Snapshotting[Key, State]] to {
      val entityBuilder = for {
        memoryEventJournal            <- ZIO.service[MemoryEventJournal[Key, Event]]
        snapshotting                  <- ZIO.service[Snapshotting[Key, State]]
        memoryEventJournalOffsetStore <- MemoryKeyValueStore.make[Key, Long]
        baseAlgebraConfig = AlgebraCombinatorConfig.build[Key, State, Event](
          memoryEventJournalOffsetStore,
          tagging,
          memoryEventJournal,
          snapshotting
        )
        cache       <- Ref.make[Map[Key, UIO[Combinators[State, Event, Reject]]]](Map.empty)
        localEntity <- LocalRuntimeWithProtocol.buildLocalEntity(eventSourcedBehaviour, baseAlgebraConfig, cache)
      } yield localEntity
      (for {
        entity <- entityBuilder
        probe  <- ZIO.service[EntityProbe[Key, State, Event]]
      } yield Has.allOf(entity, probe)).toLayerMany
    }

}

trait EntityProbe[Key, State, Event] {

  def apply(key: Key): KeyedProbeOperations[State, Event]

  def eventsFromReadSide(tag: EventTag): RIO[Clock, List[Event]]

  def eventStreamFromReadSide(tag: EventTag): ZStream[Clock, Throwable, Event]
}

object EntityProbe {

  case class KeyedProbeOperations[State, Event](
    state: Task[State],
    stateFromSnapshot: Task[Option[Versioned[State]]],
    events: Task[List[Event]],
    eventStream: ZStream[Any, Throwable, Event]
  )

  def make[Key: Tag, State: Tag, Event: Tag](
    eventHandler: Fold[State, Event]
  ): ZIO[Has[MemoryEventJournal[Key, Event]] with Has[Snapshotting[Key, State]], Nothing, EntityProbe[Key, State, Event]] =
    for {
      memoryEventJournal <- ZIO.service[MemoryEventJournal[Key, Event]]
      snapshotStore      <- ZIO.service[Snapshotting[Key, State]]
    } yield new EntityProbe[Key, State, Event] {

      def apply(key: Key): KeyedProbeOperations[State, Event] = KeyedProbeOperations(
        state = state(key),
        stateFromSnapshot = stateFromSnapshot(key),
        events = events(key),
        eventStream = eventStream(key)
      )
      private val stateFromSnapshot: Key => Task[Option[Versioned[State]]] = key => snapshotStore.load(key)
      private val state: Key => Task[State] = key => events(key).flatMap(list => eventHandler.run(Chunk.fromIterable(list)))
      private val events: Key => Task[List[Event]] = key => memoryEventJournal.getAppendedEvent(key)
      private val eventStream: Key => ZStream[Any, Throwable, Event] = key => memoryEventJournal.getAppendedStream(key)

      def eventsFromReadSide(tag: EventTag): RIO[Clock, List[Event]] =
        memoryEventJournal.currentEventsByTag(tag, None).runCollect.map(_.toList.map(_.event.payload))

      def eventStreamFromReadSide(tag: EventTag): ZStream[Clock, Throwable, Event] =
        memoryEventJournal.eventsByTag(tag, None).map(_.event.payload)

    }

}
