package zio.entity.runtime.k8dns

import zio.clock.Clock
import zio.duration.Duration
import zio.stream.ZStream
import zio.{Has, Ref, Schedule, Tag, UIO, ZIO, ZLayer}

import java.time.Instant
import scala.collection.immutable.TreeSeqMap

case class ExpirableMap[Key, Value](
  treeSeqMap: TreeSeqMap[Key, (Value, Instant)],
  expiredTime: Duration
) {
  def +(element: (Key, Value), now: Instant): ExpirableMap[Key, Value] = {
    ExpirableMap(treeSeqMap + (element._1 -> (element._2 -> now)), expiredTime)
  }

  def getAndUpdate(key: Key, now: Instant): Option[(ExpirableMap[Key, Value], Value)] = treeSeqMap.get(key).map { case (value, _) =>
    ExpirableMap(treeSeqMap.updated(key, (value, now)), expiredTime) -> value
  }

  def expire(now: Instant): ExpirableMap[Key, Value] = {
    // TODO change in order to check if queue is empty (so everything has been processed)
    def isExpired(lastAccess: Instant): Boolean = {
      (lastAccess.toEpochMilli + expiredTime.toMillis) <= now.toEpochMilli
    }
    treeSeqMap.foldLeft(this) { case (map, (key, (value, instant))) =>
      if (isExpired(instant)) {
        ExpirableMap(treeSeqMap.removed(key), expiredTime)
      } else return map
    }
  }
}

object ExpirableMap {
  def empty[Key, Value](expiration: Duration): ExpirableMap[Key, Value] =
    ExpirableMap[Key, Value](TreeSeqMap.empty[Key, (Value, Instant)](TreeSeqMap.OrderBy.Modification), expiration)
}

trait ExpiringCache[-Key, Value] {
  def get(key: Key): UIO[Option[Value]]

  def add(element: (Key, Value)): UIO[Unit]
}

object ExpiringCache {
  def get[Key: Tag, Value: Tag](key: Key): ZIO[Has[ExpiringCache[Key, Value]], Nothing, Option[Value]] = ZIO.accessM(_.get.get(key))
  def add[Key: Tag, Value: Tag](element: (Key, Value)): ZIO[Has[ExpiringCache[Key, Value]], Nothing, Unit] = ZIO.accessM(_.get.add(element))

  def live[Key: Tag, Value: Tag](expireAfter: Duration, checkEvery: Duration): ZLayer[Clock, Nothing, Has[ExpiringCache[Key, Value]]] =
    build[Key, Value](expireAfter, checkEvery).toLayer

  def build[Key: Tag, Value: Tag](expireAfter: Duration, checkEvery: Duration): ZIO[Clock, Nothing, ExpiringCache[Key, Value]] = for {
    state <- Ref.make(ExpirableMap.empty[Key, Value](expireAfter))
    clock <- ZIO.service[Clock.Service]
    _ <- ZStream
      .fromSchedule(Schedule.fixed(checkEvery))
      .foreach { _ =>
        for {
          now <- clock.instant
          _ <- state.update { old =>
            old.expire(now)
          }
        } yield ()
      }
      .fork
  } yield new ExpiringCache[Key, Value] {
    override def get(key: Key): UIO[Option[Value]] =
      for {
        now <- clock.instant
        res <- state.get.map(_.getAndUpdate(key, now))
      } yield res.map(_._2)

    override def add(element: (Key, Value)): UIO[Unit] = for {
      now <- clock.instant
      _   <- state.update(oldMap => oldMap + (element, now))
    } yield ()
  }
}
