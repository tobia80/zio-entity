package zio.entity.core

import zio.clock.Clock
import zio.duration.Duration
import zio.entity.core.journal.{EventJournal, JournalQuery, MemoryEventJournal}
import zio.entity.core.snapshot.{KeyValueStore, MemoryKeyValueStore}
import zio.entity.data.Versioned
import zio.{Has, Tag, Task, ZLayer}

case class Stores[Key, Event, State](
  snapshotStore: KeyValueStore[Key, Versioned[State]],
  journalStore: EventJournal[Key, Event] with JournalQuery[Long, Key, Event],
  offsetStore: KeyValueStore[Key, Long]
)
trait StoresFactory[Key, Event, State] {
  def buildStores(entity: String, pollingInterval: Duration): Task[Stores[Key, Event, State]]
}

object MemoryStoresFactory {
  def live[Key: Tag, Event: Tag, State: Tag]: ZLayer[Clock, Nothing, Has[StoresFactory[Key, Event, State]]] =
    ZLayer.fromService[Clock.Service, StoresFactory[Key, Event, State]](clock =>
      (entity: String, pollingInterval: Duration) => {
        for {
          snapshotStore <- MemoryKeyValueStore.make[Key, Versioned[State]]
          journalStore  <- MemoryEventJournal.make[Key, Event](pollingInterval).provideLayer(ZLayer.succeed(clock))
          offsetStore   <- MemoryKeyValueStore.make[Key, Long]
        } yield Stores(snapshotStore = snapshotStore, journalStore = journalStore, offsetStore = offsetStore)
      }
    )
}
