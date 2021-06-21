package zio.entity.core.snapshot
import zio.Task

class NoOpKeyValueStore[K, V] extends KeyValueStore[K, V] {
  override def setValue(key: K, value: V): Task[Unit] = Task.unit

  override def getValue(key: K): Task[Option[V]] = Task.succeed(None)

  override def deleteValue(key: K): Task[Unit] = Task.unit
}
