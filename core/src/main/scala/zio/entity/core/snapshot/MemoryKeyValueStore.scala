package zio.entity.core.snapshot

import zio.entity.data.Versioned
import zio.{Has, Ref, Tag, Task, UIO, ZIO, ZLayer}

class MemoryKeyValueStore[K, V](internalMap: Ref[Map[K, V]]) extends KeyValueStore[K, V] {

  override def setValue(key: K, value: V): Task[Unit] = internalMap.update { element =>
    element + (key -> value)
  }

  override def getValue(key: K): Task[Option[V]] = internalMap.get.map(_.get(key))

  override def deleteValue(key: K): Task[Unit] = internalMap.update(_ - key)
}

object MemoryKeyValueStore {
  def make[K, V]: UIO[KeyValueStore[K, V]] = Ref.make(Map.empty[K, V]).map(new MemoryKeyValueStore[K, V](_))

  def snapshotStore[K: Tag, State: Tag](pollingInterval: Int = 10): ZIO[Any, Throwable, Snapshotting[K, State]] =
    MemoryKeyValueStore.make[K, Versioned[State]].map(Snapshotting.eachVersion(pollingInterval, _))

}
