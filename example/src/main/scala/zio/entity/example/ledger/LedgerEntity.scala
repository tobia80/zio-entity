package zio.entity.example.ledger

import zio.IO
import zio.entity.annotations.Id
import zio.entity.core.{Combinators, Entity}
import zio.entity.example.Amount.Currency
import zio.entity.example.{Amount, LedgerCredited, LedgerDebited, LedgerEvent, LedgerId, LedgerLockAdded, LedgerLockReleased, LedgerLockSettled, Lock, LockId}

class LedgerEntityCommandHandler(combinators: Combinators[Ledger, LedgerEvent, LedgerError]) {
  import combinators._

  @Id(1)
  def credit(reason: String, amount: Amount): IO[LedgerError, Unit] = append(LedgerCredited(reason, amount))

  @Id(2)
  def debit(reason: String, amount: Amount): IO[LedgerError, Boolean] = for {
    currentState <- read
    result       <- if (currentState.isAvailable(amount)) append(LedgerDebited(reason, amount)).as(true) else IO.succeed(false)
  } yield result

  @Id(3)
  def lockAmount(reason: String, lock: Lock): IO[LedgerError, Boolean] = for {
    currentState <- read
    result       <- if (currentState.isAvailable(lock.getAmount)) append(LedgerLockAdded(reason, lock)).as(true) else IO.succeed(false)
  } yield result

  @Id(4)
  def settleLock(lockId: LockId): IO[LedgerError, Boolean] = for {
    currentState <- read
    result = currentState.locks.exists(_.lockId.contains(lockId))
    _ <- append(LedgerLockSettled(lockId))
  } yield result

  @Id(5)
  def releaseLock(lockId: LockId): IO[LedgerError, Boolean] = {
    for {
      currentState <- read
      result = currentState.locks.exists(_.lockId.contains(lockId))
      _ <- append(LedgerLockReleased(lockId))
    } yield result
  }

  @Id(6)
  def getLedger: IO[LedgerError, Ledger] = read
}

sealed trait LedgerError
case class Ledger(locks: List[Lock], actual: Map[Currency, BigDecimal]) {
  def available: Map[Currency, BigDecimal] = Map.empty

  def isAvailable(amount: Amount): Boolean = {
    available.get(amount.currency).fold[Boolean](false)(value => value > amount.getValue)
  }
}

object LedgerEntity {
  type LedgerEntity = Entity[LedgerId, LedgerEntityCommandHandler, Ledger, LedgerEvent, LedgerError]
}

case object UnknownLedgerError extends LedgerError
