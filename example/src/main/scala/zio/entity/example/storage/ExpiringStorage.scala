package zio.entity.example.storage

import zio.{Has, Ref, Tag, Task, ULayer}

import java.time.Instant

trait ExpiringStorage[K, V] {

  def insert(key: K, value: V, expireOn: Instant): Task[Unit]

  def findExpired(now: Instant): Task[Set[(K, V)]]

  def delete(key: K): Task[Unit]
}

object MemoryExpiringStorage {
  private case class Value[V](value: V, expireOn: Instant)
  def live[K: Tag, V: Tag]: ULayer[Has[ExpiringStorage[K, V]]] = (for {
    internal <- Ref.make[Map[K, Value[V]]](Map.empty)
  } yield new ExpiringStorage[K, V] {
    override def insert(key: K, value: V, expireOn: Instant): Task[Unit] = internal.update { oldMap =>
      oldMap + (key -> Value(value, expireOn))
    }
    override def delete(key: K): Task[Unit] = internal.update { oldMap =>
      oldMap - key
    }

    override def findExpired(now: Instant): Task[Set[(K, V)]] = internal.get.map { internalMap =>
      internalMap.toSet.filter(el => el._2.expireOn.isBefore(now)).map(el => el._1 -> el._2.value)
    }
  }).toLayer
}
