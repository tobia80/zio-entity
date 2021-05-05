package zio.entity.core.snapshot

import zio.Task

trait KeyValueStore[K, A] { self =>
  def setValue(key: K, value: A): Task[Unit]
  def getValue(key: K): Task[Option[A]]
  def deleteValue(key: K): Task[Unit]
  def takeValue(key: K): Task[Option[A]] =
    getValue(key) <* deleteValue(key)

  final def contramap[K2](f: K2 => K): KeyValueStore[K2, A] = new KeyValueStore[K2, A] {
    override def setValue(key: K2, value: A): Task[Unit] = self.setValue(f(key), value)
    override def getValue(key: K2): Task[Option[A]] = self.getValue(f(key))
    override def deleteValue(key: K2): Task[Unit] = self.deleteValue(f(key))
  }
}
