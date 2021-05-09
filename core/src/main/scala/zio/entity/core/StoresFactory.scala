package zio.entity.core

import zio.clock.Clock
import zio.duration.Duration
import zio.entity.core.journal.{EventJournal, JournalQuery, MemoryEventJournal}
import zio.entity.core.snapshot.{KeyValueStore, MemoryKeyValueStore, Snapshotting}
import zio.entity.data.Versioned
import zio.{Has, IO, Tag, Task, ZLayer}

case class Stores[Key, Event, State](
  snapshotting: Snapshotting[Key, State],
  journalStore: EventJournal[Key, Event] with JournalQuery[Long, Key, Event],
  offsetStore: KeyValueStore[Key, Long]
)
trait StoresFactory[Key, Event, State] {
  def buildStores[E](entity: String, pollingInterval: Duration, snapshotEvery: Int): IO[E, Stores[Key, Event, State]]
}

object MemoryStoresFactory {
  def live[Key: Tag, Event: Tag, State: Tag]: ZLayer[Clock, Nothing, Has[StoresFactory[Key, Event, State]]] =
    ZLayer.fromService[Clock.Service, StoresFactory[Key, Event, State]](clock =>
      new StoresFactory[Key, Event, State] {
        override def buildStores[E](entity: String, pollingInterval: Duration, snapshotEvery: Int): IO[E, Stores[Key, Event, State]] = for {
          snapshotStore <- MemoryKeyValueStore.make[Key, Versioned[State]]
          journalStore  <- MemoryEventJournal.make[Key, Event](pollingInterval).provideLayer(ZLayer.succeed(clock))
          offsetStore   <- MemoryKeyValueStore.make[Key, Long]
        } yield Stores(snapshotting = Snapshotting.eachVersion(snapshotEvery, snapshotStore), journalStore = journalStore, offsetStore = offsetStore)
      }
    )
}
