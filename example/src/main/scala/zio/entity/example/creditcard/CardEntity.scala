package zio.entity.example.creditcard

import zio.{IO, UIO}
import zio.entity.core.{Combinators, Entity}
import zio.entity.example.{Amount, Card, CardClosed, CardEvent, CardOpened, Closed, Created, LedgerId, Opened}

import java.time.Instant
import java.util.UUID

// 1 ledger, many cards
class CardCommandHandler(combinators: Combinators[Card, CardEvent, CardError]) {
  import combinators._

  def open(name: String, ledgerId: LedgerId): IO[CardError, Card] = for {
    currentState <- read
    newOrOldState <- currentState match {
      case _: Created =>
        append(CardOpened(name, Some(ledgerId)))
          .as(Opened(name, Some(ledgerId)))
      case _ => IO.fail(CardInWrongState(currentState))
    }
  } yield newOrOldState

  def close: IO[CardError, Card] = for {
    opened <- failIfNotOpen(read)
    res    <- append(CardClosed()).as(Closed(opened.name, opened.ledgerId))
  } yield res

  private def failIfNotOpen(read: IO[CardError, Card]): IO[CardError, Opened] = read.flatMap {
    case a: Opened    => UIO.succeed(a)
    case currentState => IO.fail(CardInWrongState(currentState))
  }

//  def authorize(amount: Amount, when: Instant): IO[CardError, Option[AuthId]] = for {
//    opened <- failIfNotOpen(read)
//    state  <- ledger(ledgerId).status
//    res    <- opened
//  } yield res

//  def release(authId: AuthId, when: Instant): IO[CardError, Boolean] = ???
//
//  def clear(authId: AuthId, when: Instant): IO[CardError, Boolean] = ???

  def status: IO[CardError, Card] = read
}

sealed trait CardError

case class CardInWrongState(card: Card) extends CardError

case object UnknownCardError extends CardError

case class CardId(value: UUID) extends AnyVal

case class AuthId(value: String) extends AnyVal

object CardEntity {
  type CardEntity = Entity[CardId, CardCommandHandler, Card, CardEvent, CardError]
}
