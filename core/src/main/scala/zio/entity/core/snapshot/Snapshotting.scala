package zio.entity.core.snapshot

import zio.Task
import zio.entity.data.Versioned

final class SnapshotEach[K, S](interval: Long, store: KeyValueStore[K, Versioned[S]]) extends Snapshotting[K, S] {
  def snapshot(key: K, currentState: Versioned[S], nextState: Versioned[S]): Task[Unit] =
    if (nextState.version % interval == 0 || ((currentState.version / interval) - (nextState.version / interval)) > 0)
      store.setValue(key, nextState)
    else
      Task.unit
  override def load(k: K): Task[Option[Versioned[S]]] =
    store.getValue(k)
}

final class NoSnapshot[K, S] extends Snapshotting[K, S] {
  private val void = Task.unit
  private val empty: Task[Option[Versioned[S]]] = Task.none
  override def snapshot(key: K, currentState: Versioned[S], nextState: Versioned[S]): Task[Unit] =
    void
  override def load(k: K): Task[Option[Versioned[S]]] =
    empty
}

trait Snapshotting[K, S] {
  def snapshot(key: K, before: Versioned[S], after: Versioned[S]): Task[Unit]
  def load(k: K): Task[Option[Versioned[S]]]
}

object Snapshotting {
  def eachVersion[K, S](
    interval: Long,
    store: KeyValueStore[K, Versioned[S]]
  ): Snapshotting[K, S] =
    new SnapshotEach(interval, store)

  def disabled[K, S]: Snapshotting[K, S] = new NoSnapshot
}
