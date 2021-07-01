package zio.entity.example.creditcard.readside

import zio.entity.data.{ConsumerId, EventTag, Tagging}
import zio.entity.example.creditcard.CardEntity.CardEntity
import zio.entity.example.creditcard.{CardError, CardId, UnknownCardError}
import zio.entity.example.ledger.LedgerEntity.LedgerEntity
import zio.entity.readside.ReadSideParams
import zio.{IO, UIO, ZLayer}

trait LockReleaser {
  def process: IO[LockReleaserError, Unit]
}

object LockReleaser {
  val live = ZLayer.fromServices[CardEntity, LedgerEntity, LockReleaser] { (cardEntity, ledgerEntity) =>
    val logic: (CardId, CardEvent) => IO[CardError, Unit] = (id, event) => {
      event match {
        case AuthExpired(ledgerId, lockId) => ledgerEntity(ledgerId.get).releaseLock(lockId.get).mapError(_ => UnknownCardError)
        case _                             => UIO.unit
      }
    }
    val readSideParams: ReadSideParams[CardId, CardEvent, CardError] =
      ReadSideParams("LockReleaser", ConsumerId("lockReleaser"), Tagging.const(EventTag("Card")), 1, logic)
    new LockReleaser {

      def process: IO[LockReleaserError, Unit] =
        cardEntity.readSideSubscription(readSideParams, _ => UnknownCardError).unit.mapError(_ => UnknownLockReleaserError)
    }
  }

}

sealed trait LockReleaserError

case object UnknownLockReleaserError extends LockReleaserError
