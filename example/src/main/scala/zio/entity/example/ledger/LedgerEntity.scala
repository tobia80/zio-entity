package zio.entity.example.ledger

import zio.entity.annotations.Id
import zio.entity.core.Fold.impossible
import zio.entity.core.{Combinators, Entity, Fold}
import zio.entity.data.Tagging.Const
import zio.entity.data.{EntityProtocol, EventTag, Tagging}
import zio.entity.example.Amount.Currency
import zio.entity.example.{Amount, LedgerCredited, LedgerDebited, LedgerEvent, LedgerId, LedgerLockAdded, LedgerLockReleased, LedgerLockSettled, Lock, LockId}
import zio.entity.macros.RpcMacro
import zio.{Has, IO, UIO, ZIO}

trait Ledger {

  @Id(1)
  def credit(reason: String, amount: Amount): IO[LedgerError, Unit]

  @Id(2)
  def debit(reason: String, amount: Amount): IO[LedgerError, Boolean]

  @Id(3)
  def lockAmount(reason: String, lock: Lock): IO[LedgerError, Boolean]

  @Id(4)
  def settleLock(lockId: LockId): IO[LedgerError, Boolean]

  @Id(5)
  def releaseLock(lockId: LockId): IO[LedgerError, Boolean]

  @Id(6)
  def getLedger: IO[LedgerError, LedgerState]

}

class LedgerEntityCommandHandler(combinators: Combinators[LedgerState, LedgerEvent, LedgerError]) extends Ledger {
  import combinators._

  def credit(reason: String, amount: Amount): IO[LedgerError, Unit] = append(LedgerCredited(reason, amount))

  def debit(reason: String, amount: Amount): IO[LedgerError, Boolean] = for {
    currentState <- read
    result       <- if (currentState.isAvailable(amount)) append(LedgerDebited(reason, amount)).as(true) else IO.succeed(false)
  } yield result

  def lockAmount(reason: String, lock: Lock): IO[LedgerError, Boolean] = for {
    //TODO check first if lock id is already present
    currentState <- read
    result       <- if (currentState.isAvailable(lock.amount)) append(LedgerLockAdded(reason, lock)).as(true) else IO.succeed(false)
  } yield result

  def settleLock(lockId: LockId): IO[LedgerError, Boolean] = for {
    currentState <- read
    result = currentState.locks.exists(_.lockId == lockId)
    _ <- append(LedgerLockSettled(lockId))
  } yield result

  def releaseLock(lockId: LockId): IO[LedgerError, Boolean] = {
    for {
      currentState <- read
      result = currentState.locks.exists(_.lockId == lockId)
      _ <- append(LedgerLockReleased(lockId))
    } yield result
  }

  def getLedger: IO[LedgerError, LedgerState] = read

}

sealed trait LedgerError
case class LedgerState(locks: List[Lock], actual: Map[Currency, BigDecimal]) {
  def available: Map[Currency, BigDecimal] = Map.empty

  def isAvailable(amount: Amount): Boolean = {
    available.get(amount.currency).fold[Boolean](false)(value => value > amount.getValue)
  }
}

object LedgerEntity {

  val tagging: Const[LedgerId] = Tagging.const[LedgerId](EventTag("Ledger"))

  val eventHandlerLogic: Fold[LedgerState, LedgerEvent] = Fold(
    initial = LedgerState(Nil, Map.empty),
    reduce = {
      case (state, LedgerLockAdded(_, lock))   => UIO.succeed(state.copy(locks = state.locks :+ lock))
      case (state, LedgerLockReleased(lockId)) => UIO.succeed(state.copy(locks = state.locks.filterNot(_.lockId == lockId)))
      case (state, LedgerLockSettled(lockId)) =>
        val foundLock = state.locks.find(_.lockId == lockId)
        foundLock match {
          case Some(lock) =>
            val newActual: Map[Currency, BigDecimal] =
              state.actual.updatedWith(lock.amount.currency) { oldValue =>
                val newValue = oldValue.getOrElse(BigDecimal(0)) - lock.amount.getValue
                Some(newValue)
              }
            UIO.succeed(state.copy(actual = newActual, locks = state.locks.filterNot(_.lockId == lockId)))

          case None => UIO.succeed(state)
        }

      case (state, LedgerDebited(_, amount)) =>
        UIO.succeed(state.copy(actual = state.actual.updatedWith(amount.currency) { oldValue =>
          val newValue = oldValue.getOrElse(BigDecimal(0)) - amount.getValue
          Some(newValue)
        }))
      case (state, LedgerCredited(_, amount)) =>
        UIO.succeed(state.copy(actual = state.actual.updatedWith(amount.currency) { oldValue =>
          val newValue = oldValue.getOrElse(BigDecimal(0)) + amount.getValue
          Some(newValue)
        }))
      case _ => impossible
    }
  )

  implicit val ledgerProtocol: EntityProtocol[Ledger, LedgerError] = RpcMacro.derive[Ledger, LedgerError]

  type LedgerEntity = Entity[LedgerId, Ledger, LedgerState, LedgerEvent, LedgerError]

  def apply(id: LedgerId): ZIO[Has[LedgerEntity], Nothing, Ledger] = ZIO.access[Has[LedgerEntity]](_.get.apply(id))
}

case object UnknownLedgerError extends LedgerError
