package zio.entity.test

import zio.blocking.Blocking
import zio.clock.Clock
import zio.duration.durationInt
import zio.entity.core._
import zio.entity.core.journal.TestEventStore
import zio.entity.data.{EntityProtocol, EventTag, Tagging, Versioned}
import zio.entity.readside.{KillSwitch, ReadSideParams}
import zio.entity.test.EntityProbe.KeyedProbeOperations
import zio.stream.ZStream
import zio.test.environment.TestClock
import zio.{Chunk, Has, Hub, RIO, Ref, Tag, Task, UIO, URIO, ZIO, ZLayer}

object TestEntityRuntime {

  object TestReadSideProcessor {
    trait TestReadSideProcessor[Reject] {
      def triggerReadSideProcessing(triggerTimes: Int): URIO[TestClock, Unit]

      def triggerReadSideAndWaitFor(triggerTimes: Int, messagesToWaitFor: Int): ZIO[TestClock with Clock with Blocking, Reject, Unit]

    }
  }

  trait TestEntity[Key, Algebra, State, Event, Reject] extends EntityProbe[Key, State, Event] with TestReadSideEntity[Key, Event, Reject]

  object TestEntity {
    def probe[Key: Tag, Algebra: Tag, State: Tag, Event: Tag, Reject: Tag]: URIO[Has[
      TestEntity[Key, Algebra, State, Event, Reject]
    ], TestEntity[Key, Algebra, State, Event, Reject]] =
      ZIO.service[TestEntity[Key, Algebra, State, Event, Reject]]
  }

  def testEntityWithProbe[Key: Tag, Algebra: Tag, State: Tag, Event: Tag, Reject: Tag]: URIO[Has[Entity[Key, Algebra, State, Event, Reject]] with Has[
    TestEntity[Key, Algebra, State, Event, Reject]
  ], (Entity[Key, Algebra, State, Event, Reject], TestEntity[Key, Algebra, State, Event, Reject])] =
    ZIO.services[Entity[Key, Algebra, State, Event, Reject], TestEntity[Key, Algebra, State, Event, Reject]]

  def testEntity[Key: Tag, Algebra: Tag, State: Tag, Event: Tag, Reject: Tag](
    tagging: Tagging[Key],
    eventSourcedBehaviour: EventSourcedBehaviour[Algebra, State, Event, Reject]
  )(implicit
    protocol: EntityProtocol[Algebra, Reject]
  ): ZLayer[Clock with Has[Stores[Key, Event, State] with TestEventStore[Key, Event]], Throwable, Has[Entity[Key, Algebra, State, Event, Reject]] with Has[
    TestEntity[Key, Algebra, State, Event, Reject]
  ]] = {

    (for {
      clock  <- ZIO.service[Clock.Service]
      stores <- ZIO.service[Stores[Key, Event, State] with TestEventStore[Key, Event]]
      baseAlgebraConfig = AlgebraCombinatorConfig[Key, State, Event](
        stores.offsetStore,
        tagging,
        stores.journalStore,
        stores.snapshotting
      )
      cache <- Ref.make[Map[Key, UIO[Combinators[State, Event, Reject]]]](Map.empty)
      // TODO create an entity that extends Probe and TestReadSide
      entity <- LocalRuntimeWithProtocol.buildLocalEntity(
        eventSourcedBehaviour,
        baseAlgebraConfig,
        cache,
        clock,
        stores.committableJournalStore
      )
      probe <- EntityProbe.make[Key, State, Event](eventSourcedBehaviour.eventHandler)
      hub   <- Hub.unbounded[Unit]
      probedEntity = new Entity[Key, Algebra, State, Event, Reject] with TestEntity[Key, Algebra, State, Event, Reject] {
        override def apply(
          key: Key
        ): Algebra = entity.apply(key)

        override def probeForKey(key: Key): KeyedProbeOperations[State, Event] = probe.probeForKey(key)

        override def eventsFromReadSide(tag: EventTag): RIO[Clock, List[Event]] = probe.eventsFromReadSide(tag)

        override def eventStreamFromReadSide(tag: EventTag): ZStream[Clock, Throwable, Event] = probe.eventStreamFromReadSide(tag)

        private val consumed = ZStream.fromHub(hub)

        override def readSideStream(
          readSideParams: ReadSideParams[Key, Event, Reject],
          errorHandler: Throwable => Reject
        ): ZStream[Any, Reject, KillSwitch] = for {
          ks <- entity.readSideStream(readSideParams, errorHandler)
        } yield ks

        def consume(n: Int): ZIO[Any, Nothing, Boolean] = hub.publishAll(List.fill(n)(()))
        override def triggerReadSideProcessing(triggerTimes: Int): URIO[TestClock, Unit] = TestClock.adjust((triggerTimes * 50).millis)

        override def triggerReadSideAndWaitFor(
          readSideParams: ReadSideParams[Key, Event, Reject],
          errorHandler: Throwable => Reject
        )(triggerTimes: Int, messagesToWaitFor: Int): ZIO[TestClock with Clock with Blocking, Reject, Unit] = for {
          fiber <- readSideStream(readSideParams, errorHandler).take(messagesToWaitFor).runDrain.fork
          _     <- triggerReadSideProcessing(triggerTimes)
          _     <- fiber.join
        } yield ()

      }
    } yield Has[Entity[Key, Algebra, State, Event, Reject]](probedEntity) ++ Has[TestEntity[Key, Algebra, State, Event, Reject]](probedEntity)).toLayerMany
  }
}

trait EntityProbe[Key, State, Event] {

  def probeForKey(key: Key): KeyedProbeOperations[State, Event]

  def eventsFromReadSide(tag: EventTag): RIO[Clock, List[Event]]

  def eventStreamFromReadSide(tag: EventTag): ZStream[Clock, Throwable, Event]

}

trait TestReadSideEntity[Key, Event, Reject] {
  def triggerReadSideProcessing(triggerTimes: Int): URIO[TestClock, Unit]

  def triggerReadSideAndWaitFor(
    readSideParams: ReadSideParams[Key, Event, Reject],
    errorHandler: Throwable => Reject
  )(triggerTimes: Int, messagesToWaitFor: Int): ZIO[TestClock with Clock with Blocking, Reject, Unit]

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
  ): ZIO[Has[Stores[Key, Event, State] with TestEventStore[Key, Event]], Nothing, EntityProbe[
    Key,
    State,
    Event
  ]] =
    for {
      stores <- ZIO.service[Stores[Key, Event, State] with TestEventStore[Key, Event]]
//      memoryEventJournal <- ZIO.service[MemoryEventJournal[Key, Event]]
//      snapshotStore      <- ZIO.service[Snapshotting[Key, State]]
    } yield new EntityProbe[Key, State, Event] {

      def probeForKey(key: Key): KeyedProbeOperations[State, Event] = KeyedProbeOperations(
        state = state(key),
        stateFromSnapshot = stateFromSnapshot(key),
        events = events(key),
        eventStream = eventStream(key)
      )
      private val stateFromSnapshot: Key => Task[Option[Versioned[State]]] = key => stores.snapshotting.load(key)
      private val state: Key => Task[State] = key => events(key).flatMap(list => eventHandler.run(Chunk.fromIterable(list)))
      private val events: Key => Task[List[Event]] = key => stores.getAppendedEvent(key)
      private val eventStream: Key => ZStream[Any, Throwable, Event] = key => stores.getAppendedStream(key)

      def eventsFromReadSide(tag: EventTag): RIO[Clock, List[Event]] =
        stores.journalStore.currentEventsByTag(tag, None).runCollect.map(_.toList.map(_.event.payload))

      def eventStreamFromReadSide(tag: EventTag): ZStream[Clock, Throwable, Event] =
        stores.journalStore.eventsByTag(tag, None).map(_.event.payload)

    }

}
