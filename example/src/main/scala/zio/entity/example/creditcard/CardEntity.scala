package zio.entity.example.creditcard

import zio.entity.core.Fold.impossible
import zio.entity.core.{Combinators, Entity, Fold}
import zio.entity.data.Tagging.Const
import zio.entity.data.{EntityProtocol, EventTag, Tagging}
import zio.entity.example.{CardClosed, CardEvent, CardOpened, CardState, Closed, Created, LedgerId, Opened}
import zio.entity.macros.RpcMacro
import zio.{Has, IO, UIO, ZIO}

import java.util.UUID

trait Card {

  def open(name: String, ledgerId: LedgerId): IO[CardError, CardState]

  def close: IO[CardError, CardState]

  def status: IO[CardError, CardState]
}
// 1 ledger, many cards
class CardCommandHandler(combinators: Combinators[CardState, CardEvent, CardError]) extends Card {
  import combinators._

  def open(name: String, ledgerId: LedgerId): IO[CardError, CardState] = for {
    currentState <- read
    newOrOldState <- currentState match {
      case _: Created =>
        append(CardOpened(name, Some(ledgerId)))
          .as(Opened(name, Some(ledgerId)))
      case _ => IO.fail(CardInWrongState(currentState))
    }
  } yield newOrOldState

  def close: IO[CardError, CardState] = for {
    opened <- read.flatMap {
      case a: Opened    => UIO.succeed(a)
      case currentState => IO.fail(CardInWrongState(currentState))
    }
    res <- append(CardClosed()).as(Closed(opened.name, opened.ledgerId))
  } yield res

  def status: IO[CardError, CardState] = read
}

sealed trait CardError

case class CardInWrongState(card: CardState) extends CardError

case object UnknownCardError extends CardError

case class CardId(value: UUID) extends AnyVal

case class AuthId(value: String) extends AnyVal

object CardEntity {
  type CardEntity = Entity[CardId, Card, CardState, CardEvent, CardError]

  def apply(id: CardId): ZIO[Has[CardEntity], Nothing, Card] = ZIO.access(_.get.apply(id))

  val tagging: Const[CardId] = Tagging.const[CardId](EventTag("Card"))

  val eventHandlerLogic: Fold[CardState, CardEvent] = Fold(
    initial = Created(),
    reduce = {
      case (a: Created, CardOpened(name, ledgerId)) => UIO.succeed(Opened(name, ledgerId))
      case (a: Opened, ev: CardClosed)              => UIO.succeed(Closed(a.name, a.ledgerId))
      case _                                        => impossible
    }
  )

  implicit val cardProtocol: EntityProtocol[Card, CardError] =
    RpcMacro.derive[Card, CardError]
}
