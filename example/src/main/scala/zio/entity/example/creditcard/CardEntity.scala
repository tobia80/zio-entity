package zio.entity.example.creditcard

import zio.{IO, UIO}
import zio.entity.core.{Combinators, Entity}
import zio.entity.example.{Card, CardClosed, CardEvent, CardOpened, Closed, Created, LedgerId, Opened}

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
    opened <- read.flatMap {
      case a: Opened    => UIO.succeed(a)
      case currentState => IO.fail(CardInWrongState(currentState))
    }
    res <- append(CardClosed()).as(Closed(opened.name, opened.ledgerId))
  } yield res

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
