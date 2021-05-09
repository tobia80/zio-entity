package zio.entity.test

import zio.{Has, IO, Tag, Task, ZLayer}
import zio.clock.Clock
import zio.duration.{durationInt, Duration}
import zio.entity.core.{Stores, StoresFactory}
import zio.entity.core.journal.{MemoryEventJournal, TestEventStore}
import zio.entity.core.snapshot.{MemoryKeyValueStore, Snapshotting}
import zio.entity.data.Versioned
import zio.stream.ZStream

object TestMemoryStoresFactory {

  def live[Key: Tag, Event: Tag, State: Tag](
    polling: Duration = 300.millis
  ): ZLayer[Clock, Nothing, Has[StoresFactory[Key, Event, State] with TestEventStore[Key, Event]]] =
    ZLayer.fromServiceM[Clock.Service, Any, Nothing, StoresFactory[Key, Event, State] with TestEventStore[Key, Event]] { clock =>
      val memoryStoreM = MemoryEventJournal.make[Key, Event](polling).provideLayer(ZLayer.succeed(clock))
      memoryStoreM.map { journalStore =>
        new StoresFactory[Key, Event, State] with TestEventStore[Key, Event] {
          override def buildStores[E](entity: String, pollingInterval: Duration, snapshotEvery: Int): IO[E, Stores[Key, Event, State]] = for {
            snapshotStore <- MemoryKeyValueStore.make[Key, Versioned[State]]
            offsetStore   <- MemoryKeyValueStore.make[Key, Long]
          } yield Stores(snapshotting = Snapshotting.eachVersion(snapshotEvery, snapshotStore), journalStore = journalStore, offsetStore = offsetStore)

          override def getAppendedEvent(key: Key): Task[List[Event]] = journalStore.getAppendedEvent(key)

          override def getAppendedStream(key: Key): ZStream[Any, Nothing, Event] = journalStore.getAppendedStream(key)
        }
      }
    }

}
