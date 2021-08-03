package zio.entity.example.creditcard.readside

import zio.entity.data.{ConsumerId, EventTag, Tagging}
import zio.entity.example.ledger.LedgerEntity.LedgerEntity
import zio.entity.example.ledger.{LedgerError, UnknownLedgerError}
import zio.entity.example.storage.ExpiringStorage
import zio.entity.example.{LedgerEvent, LedgerId, LedgerLockAdded, LedgerLockReleased, LockId}
import zio.entity.readside.ReadSideParams
import zio.{IO, UIO, ZLayer}

import java.time.Instant

trait ActiveLocksTracker {
  def process: IO[ActiveLocksTrackerError, Unit]
}

object ActiveLocksTracker {

  case class LockKey(ledgerId: LedgerId, lockId: LockId)
  case class LockValue(expiredAt: Instant)
  val live = ZLayer.fromServices[ExpiringStorage[LockKey, LockValue], LedgerEntity, ActiveLocksTracker] { (storage, ledgerEntity) =>
    val logic: (LedgerId, LedgerEvent) => IO[LedgerError, Unit] = (id, event) =>
      {
        event match {
          case lockAdded: LedgerLockAdded =>
            val lockKey = LockKey(id, lockAdded.lock.lockId)
            storage.insert(lockKey, LockValue(lockAdded.lock.getExpiredOn.asJavaInstant), lockAdded.lock.getExpiredOn.asJavaInstant)
          case lockReleased: LedgerLockReleased =>
            val lockKey = LockKey(id, lockReleased.lockId)
            storage.delete(lockKey)
          case _ => UIO.unit
        }
      }.mapError(_ => UnknownLedgerError)
    val readSideParams: ReadSideParams[LedgerId, LedgerEvent, LedgerError] =
      ReadSideParams(
        name = "ActiveLocksTracker",
        consumerId = ConsumerId("ActiveLocksTracker"),
        tagging = Tagging.const(EventTag("Ledger")),
        parallelism = 1,
        logic = logic
      )
    new ActiveLocksTracker {
      def process: IO[ActiveLocksTrackerError, Unit] =
        ledgerEntity.readSideSubscription(readSideParams, _ => UnknownLedgerError).unit.mapError(_ => UnknownActiveLocksTrackerError$)
    }
  }
}

sealed trait ActiveLocksTrackerError

case object UnknownActiveLocksTrackerError$ extends ActiveLocksTrackerError
