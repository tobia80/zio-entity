package zio.entity.test

import zio.blocking.Blocking
import zio.clock.Clock
import zio.duration.durationInt
import zio.entity.core._
import zio.entity.core.journal.{CommittableJournalQuery, MemoryEventJournal, TestEventStore}
import zio.entity.core.snapshot.{MemoryKeyValueStore, Snapshotting}
import zio.entity.data.{EntityProtocol, EventTag, Tagging, Versioned}
import zio.entity.readside
import zio.entity.readside.{KillSwitch, ReadSideParams, ReadSideProcessing, ReadSideProcessor}
import zio.entity.test.EntityProbe.KeyedProbeOperations
import zio.stream.ZStream
import zio.test.environment.TestClock
import zio.{duration, Chunk, Fiber, Has, RIO, Ref, Tag, Task, UIO, URIO, ZIO, ZLayer}

import scala.concurrent.duration.Duration

object TestEntityRuntime {

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
    Has[Entity[Key, Algebra, State, Event, Reject]] with Has[EntityProbe[Key, State, Event]]

  def entity[Key: Tag, Algebra: Tag, State: Tag, Event: Tag, Reject: Tag]
    : URIO[Has[Entity[Key, Algebra, State, Event, Reject]], Entity[Key, Algebra, State, Event, Reject]] =
    ZIO.service[Entity[Key, Algebra, State, Event, Reject]]

  def testEntityWithProbes[Key: Tag, Algebra: Tag, State: Tag, Event: Tag, Reject: Tag]
    : URIO[TestEntity[Key, Algebra, State, Event, Reject], (Entity[Key, Algebra, State, Event, Reject], EntityProbe[Key, State, Event])] =
    ZIO.services[Entity[Key, Algebra, State, Event, Reject], EntityProbe[Key, State, Event]]

  def testEntity[Key: Tag, Algebra: Tag, State: Tag, Event: Tag, Reject: Tag](
    tagging: Tagging[Key],
    eventSourcedBehaviour: EventSourcedBehaviour[Algebra, State, Event, Reject],
    pollingInterval: duration.Duration,
    snapEvery: Int
  )(implicit
    protocol: EntityProtocol[Algebra, State, Event, Reject]
  ): ZLayer[Has[StoresFactory[Key, Event, State] with TestEventStore[Key, Event]], Throwable, TestEntity[Key, Algebra, State, Event, Reject]] = {
    (for {
      storesFactory <- ZIO.service[StoresFactory[Key, Event, State] with TestEventStore[Key, Event]]
      stores        <- storesFactory.buildStores("test", pollingInterval, snapEvery)
      baseAlgebraConfig = AlgebraCombinatorConfig.build[Key, State, Event](
        stores.offsetStore,
        tagging,
        stores.journalStore,
        stores.snapshotting
      )
      cache  <- Ref.make[Map[Key, UIO[Combinators[State, Event, Reject]]]](Map.empty)
      entity <- LocalRuntimeWithProtocol.buildLocalEntity(eventSourcedBehaviour, baseAlgebraConfig, cache)
      probe  <- EntityProbe.make[Key, State, Event](eventSourcedBehaviour.eventHandler)
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
    eventHandler: Fold[State, Event],
    pollingInterval: duration.Duration = 10.millis
  ): ZIO[Has[StoresFactory[Key, Event, State] with TestEventStore[Key, Event]], Nothing, EntityProbe[
    Key,
    State,
    Event
  ]] =
    for {
      storesFactory <- ZIO.service[StoresFactory[Key, Event, State] with TestEventStore[Key, Event]]
      stores        <- storesFactory.buildStores("test", pollingInterval, 2)
//      memoryEventJournal <- ZIO.service[MemoryEventJournal[Key, Event]]
//      snapshotStore      <- ZIO.service[Snapshotting[Key, State]]
    } yield new EntityProbe[Key, State, Event] {

      def apply(key: Key): KeyedProbeOperations[State, Event] = KeyedProbeOperations(
        state = state(key),
        stateFromSnapshot = stateFromSnapshot(key),
        events = events(key),
        eventStream = eventStream(key)
      )
      private val stateFromSnapshot: Key => Task[Option[Versioned[State]]] = key => stores.snapshotting.load(key)
      private val state: Key => Task[State] = key => events(key).flatMap(list => eventHandler.run(Chunk.fromIterable(list)))
      private val events: Key => Task[List[Event]] = key => storesFactory.getAppendedEvent(key)
      private val eventStream: Key => ZStream[Any, Throwable, Event] = key => storesFactory.getAppendedStream(key)

      def eventsFromReadSide(tag: EventTag): RIO[Clock, List[Event]] =
        stores.journalStore.currentEventsByTag(tag, None).runCollect.map(_.toList.map(_.event.payload))

      def eventStreamFromReadSide(tag: EventTag): ZStream[Clock, Throwable, Event] =
        stores.journalStore.eventsByTag(tag, None).map(_.event.payload)

    }

}
