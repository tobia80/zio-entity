package zio.entity.test

import zio.clock.Clock
import zio.duration.{durationInt, Duration}
import zio.entity.core.Stores
import zio.entity.core.journal.{CommittableJournalStore, MemoryEventJournal, TestEventStore}
import zio.entity.core.snapshot.{KeyValueStore, MemoryKeyValueStore, Snapshotting}
import zio.entity.data.{TagConsumer, Versioned}
import zio.stream.ZStream
import zio.{Has, Tag, Task, ZLayer}

object TestMemoryStores {

  case class StoresForTest[Key, Event, State](
    override val snapshotting: Snapshotting[Key, State],
    override val journalStore: MemoryEventJournal[Key, Event],
    override val offsetStore: KeyValueStore[Key, Long],
    override val committableJournalStore: CommittableJournalStore[Long, Key, Event]
  ) extends Stores[Key, Event, State]
      with TestEventStore[Key, Event] {
    override def getAppendedEvent(key: Key): Task[List[Event]] = journalStore.getAppendedEvent(key)

    override def getAppendedStream(key: Key): ZStream[Any, Nothing, Event] = journalStore.getAppendedStream(key)
  }

  def live[Key: Tag, Event: Tag, State: Tag](
    polling: Duration = 300.millis,
    snapEvery: Int = 2
  ): ZLayer[Clock, Nothing, Has[Stores[Key, Event, State] with TestEventStore[Key, Event]]] =
    ZLayer.fromServiceM[Clock.Service, Any, Nothing, Stores[Key, Event, State] with TestEventStore[Key, Event]] { clock =>
      val memoryStoreM = MemoryEventJournal.make[Key, Event](polling).provideLayer(ZLayer.succeed(clock))
      for {
        journalStore        <- memoryStoreM
        snapshotStore       <- MemoryKeyValueStore.make[Key, Versioned[State]]
        offsetStore         <- MemoryKeyValueStore.make[Key, Long]
        readSideOffsetStore <- MemoryKeyValueStore.make[TagConsumer, Long]
        committableJournalStore = new CommittableJournalStore[Long, Key, Event](readSideOffsetStore, journalStore)
      } yield StoresForTest(Snapshotting.eachVersion(snapEvery, snapshotStore), journalStore, offsetStore, committableJournalStore)

    }
}
