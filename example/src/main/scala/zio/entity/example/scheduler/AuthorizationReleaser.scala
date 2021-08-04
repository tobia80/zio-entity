package zio.entity.example.scheduler

import zio.clock.Clock
import zio.duration.Duration
import zio.entity.example.creditcard.readside.ActiveLocksTracker
import zio.entity.example.creditcard.readside.ActiveLocksTracker.{LockKey, LockValue}
import zio.entity.example.ledger.LedgerEntity.LedgerEntity
import zio.entity.example.storage.ExpiringStorage
import zio.stream.ZStream
import zio.{Has, IO, Schedule, ZIO, ZLayer}

/** Poll a db table and release the lock invoking release lock on cards
  */
trait AuthorizationReleaser {
  def run: IO[AuthorizationReleaserError, Unit]
}

sealed trait AuthorizationReleaserError

object UnknownAuthorizationReleaserError extends AuthorizationReleaserError

object FixedPollAuthorizationReleaser {

  def live(
    interval: Duration
  ): ZLayer[Has[ExpiringStorage[LockKey, LockValue]] with Has[LedgerEntity] with Has[Clock.Service], Nothing, Has[AuthorizationReleaser]] =
    (for {
      clock   <- ZIO.service[Clock.Service]
      ledger  <- ZIO.service[LedgerEntity]
      storage <- ZIO.service[ExpiringStorage[LockKey, LockValue]]
    } yield new AuthorizationReleaser {

      private def runPolling: ZStream[Any, AuthorizationReleaserError, Int] =
        (for {
          _       <- ZStream.fromSchedule(Schedule.fixed(interval))
          now     <- ZStream.fromEffect(clock.instant)
          expired <- ZStream.fromEffect(storage.findExpired(now))
          _       <- ZStream.fromEffect(ZIO.foreach(expired)(el => ledger(el._1.ledgerId).releaseLock(el._1.lockId)))
        } yield expired.size).provideLayer(ZLayer.succeed(clock)).mapError(_ => UnknownAuthorizationReleaserError)

      override def run: IO[AuthorizationReleaserError, Unit] = runPolling.runDrain
    }).toLayer

}

object JobRunners {

  def startJobs: ZIO[Has[ActiveLocksTracker] with Has[AuthorizationReleaser], Nothing, Unit] = {
    for {
      locksTracker <- ZIO.service[ActiveLocksTracker]
      releaser     <- ZIO.service[AuthorizationReleaser]
      _            <- locksTracker.run.fork
      _            <- releaser.run.fork
    } yield ()
  }

}
