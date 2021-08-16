package zio.entity.core

import zio.clock.Clock
import zio.duration.Duration
import zio.entity.core.journal.{CommittableJournalStore, EventJournal, JournalQuery, MemoryEventJournal}
import zio.entity.core.snapshot.{KeyValueStore, MemoryKeyValueStore, Snapshotting}
import zio.entity.data.{TagConsumer, Versioned}
import zio.{Has, IO, Tag, ZIO, ZLayer}

trait Stores[Key, Event, State] {
  def snapshotting: Snapshotting[Key, State]
  def journalStore: EventJournal[Key, Event] with JournalQuery[Long, Key, Event]
  def offsetStore: KeyValueStore[Key, Long]
  def committableJournalStore: CommittableJournalStore[Long, Key, Event]
}

case class StoresForEntity[Key, Event, State](
  snapshotting: Snapshotting[Key, State],
  journalStore: EventJournal[Key, Event] with JournalQuery[Long, Key, Event],
  offsetStore: KeyValueStore[Key, Long],
  committableJournalStore: CommittableJournalStore[Long, Key, Event]
) extends Stores[Key, Event, State]

trait StoresFactory[Key, Event, State] {
  def buildStores[E](entity: String, pollingInterval: Duration, snapshotEvery: Int): IO[E, Stores[Key, Event, State]]
}

object MemoryStores {
  def make[Key: Tag, Event: Tag, State: Tag](
    pollingInterval: Duration,
    snapshotEvery: Int
  ): ZLayer[Clock, Nothing, Has[Stores[Key, Event, State]]] =
    (for {
      clock               <- ZIO.service[Clock.Service]
      snapshotStore       <- MemoryKeyValueStore.make[Key, Versioned[State]]
      journalStore        <- MemoryEventJournal.make[Key, Event](pollingInterval).provideLayer(ZLayer.succeed(clock))
      offsetStore         <- MemoryKeyValueStore.make[Key, Long]
      readSideOffsetStore <- MemoryKeyValueStore.make[TagConsumer, Long]
    } yield StoresForEntity(
      snapshotting = Snapshotting.eachVersion(snapshotEvery, snapshotStore),
      journalStore = journalStore,
      offsetStore = offsetStore,
      committableJournalStore = new CommittableJournalStore[Long, Key, Event](readSideOffsetStore, journalStore)
    )).toLayer

}

object NoOpStores {}
