package zio.entity.example.creditcard

import zio.entity.example.creditcard.CardEntity.CardEntity
import zio.entity.example.ledger.LedgerEntity.LedgerEntity
import zio.entity.example.{Amount, LedgerId, Lock, LockId, Opened}
import zio.{Has, IO, ZIO, ZLayer}

import java.util.UUID

trait CardOps {

  def open(name: String, ledgerId: LedgerId): IO[CardError, CardId]
  def authAmount(id: CardId, reason: String, amount: Amount): IO[CardError, Option[LockId]]
  def authSettlement(id: CardId, lockId: LockId): IO[CardError, Boolean]
  def authRelease(id: CardId, lockId: LockId): IO[CardError, Boolean]
  def debit(id: CardId, reason: String, amount: Amount): IO[CardError, Boolean]
}

object CardOps {

  def open(name: String, ledgerId: LedgerId): ZIO[Has[CardOps], CardError, CardId] = ZIO.accessM[Has[CardOps]](_.get.open(name, ledgerId))

  def authAmount(id: CardId, reason: String, amount: Amount): ZIO[Has[CardOps], CardError, Option[LockId]] =
    ZIO.accessM[Has[CardOps]](_.get.authAmount(id, reason, amount))

  def authSettlement(id: CardId, lockId: LockId): ZIO[Has[CardOps], CardError, Boolean] = ZIO.accessM(_.get.authSettlement(id, lockId))

  def authRelease(id: CardId, lockId: LockId): ZIO[Has[CardOps], CardError, Boolean] = ZIO.accessM(_.get.authRelease(id, lockId))

  def debit(id: CardId, reason: String, amount: Amount): ZIO[Has[CardOps], CardError, Boolean] = ZIO.accessM[Has[CardOps]](_.get.debit(id, reason, amount))

  val live: ZLayer[Has[CardEntity] with Has[LedgerEntity], Nothing, Has[CardOps]] = (for {
    ledger <- ZIO.service[LedgerEntity]
    card   <- ZIO.service[CardEntity]
  } yield new CardOps {
    override def authAmount(id: CardId, reason: String, amount: Amount): IO[CardError, Option[LockId]] = for {
      ledgerId <- retrieveLedgerId(id)
      lockId = LockId(Option(UUID.randomUUID()))
      result <- ledger(ledgerId).lockAmount(reason, Lock(lockId = lockId)).mapError(_ => UnknownCardError)
    } yield if (result) Some(lockId) else None

    override def authSettlement(id: CardId, lockId: LockId): IO[CardError, Boolean] = for {
      ledgerId <- retrieveLedgerId(id)
      result   <- ledger(ledgerId).settleLock(lockId).mapError[CardError](_ => UnknownCardError)
    } yield result

    override def authRelease(id: CardId, lockId: LockId): IO[CardError, Boolean] = for {
      ledgerId <- retrieveLedgerId(id)
      result   <- ledger(ledgerId).releaseLock(lockId).mapError[CardError](_ => UnknownCardError)
    } yield result

    override def debit(id: CardId, reason: String, amount: Amount): IO[CardError, Boolean] = for {
      ledgerId <- retrieveLedgerId(id)
      result   <- ledger(ledgerId).debit(reason, amount).mapError[CardError](_ => UnknownCardError)
    } yield result

    private def retrieveLedgerId(cardId: CardId): IO[CardError, LedgerId] = for {
      cardStatus <- card(cardId).status
      ledgerId <- cardStatus match {
        case a: Opened => ZIO.fromOption(a.ledgerId).mapError[CardError](_ => UnknownCardError)
        case _         => IO.fail(CardInWrongState(cardStatus))
      }
    } yield ledgerId

    override def open(name: String, ledgerId: LedgerId): IO[CardError, CardId] = {
      val cardId = CardId(UUID.randomUUID())
      card(cardId).open(name, ledgerId).as(cardId)
    }
  }).toLayer
}
