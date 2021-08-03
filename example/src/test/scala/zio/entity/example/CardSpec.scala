package zio.entity.example

import zio.{Has, UIO, ZLayer}
import zio.clock.Clock
import zio.duration.durationInt
import zio.entity.core.{Entity, EventSourcedBehaviour}
import zio.entity.example.Amount.Currency
import zio.entity.example.creditcard._
import zio.entity.example.ledger.{Ledger, LedgerEntity, LedgerEntityCommandHandler, LedgerError, LedgerState, UnknownLedgerError}
import zio.entity.test.TestEntityRuntime.testEntity
import zio.entity.test.{TestEntityRuntime, TestMemoryStores}
import zio.test.environment.{TestClock, TestEnvironment}
import zio.test.{assertTrue, DefaultRunnableSpec, TestAspect}

import java.util.UUID

object CardSpec extends DefaultRunnableSpec {
  import CardEntity.cardProtocol
  import LedgerEntity.ledgerProtocol

  private val ledger: ZLayer[Clock, Throwable, Has[Entity[LedgerId, Ledger, LedgerState, LedgerEvent, LedgerError]] with Has[
    TestEntityRuntime.TestEntity[LedgerId, Ledger, LedgerState, LedgerEvent, LedgerError]
  ]] =
    Clock.any and TestMemoryStores.live[LedgerId, LedgerEvent, LedgerState]() to
    testEntity(
      LedgerEntity.tagging,
      EventSourcedBehaviour[Ledger, LedgerState, LedgerEvent, LedgerError](
        new LedgerEntityCommandHandler(_),
        LedgerEntity.eventHandlerLogic,
        _ => UnknownLedgerError
      )
    )

  private val card: ZLayer[Clock, Throwable, Has[Entity[CardId, Card, CardState, CardEvent, CardError]] with Has[
    TestEntityRuntime.TestEntity[CardId, Card, CardState, CardEvent, CardError]
  ]] =
    Clock.any and TestMemoryStores.live[CardId, CardEvent, CardState]() to
    testEntity(
      CardEntity.tagging,
      EventSourcedBehaviour[Card, CardState, CardEvent, CardError](new CardCommandHandler(_), CardEntity.eventHandlerLogic, _ => UnknownCardError)
    )
  private val layer = (card ++ ledger) >+> CardOps.live

  private val canMakeCardTransaction = testM("Can make card transaction") {
    val ledgerId = LedgerId(Some(UUID.randomUUID()))
    for {
      ledgerEntity  <- LedgerEntity(ledgerId)
      _             <- ledgerEntity.credit("Initial credit", Amount(Currency.EUR, Some(100)))
      cardId        <- CardOps.open("Tobia", ledgerId)
      result        <- CardOps.debit(cardId, "First payment", Amount(Currency.EUR, Some(80)))
      resultFailing <- CardOps.debit(cardId, "Failing payment", Amount(Currency.EUR, Some(30)))
      result2       <- CardOps.debit(cardId, "Second payment", Amount(Currency.EUR, Some(20)))
    } yield assertTrue(result && result2) && assertTrue(!resultFailing)
  }

  private val canUseAuth = testM("Can use authorization transactions") {
    val ledgerId = LedgerId(Some(UUID.randomUUID()))
    for {
      ledgerEntity     <- LedgerEntity(ledgerId)
      _                <- ledgerEntity.credit("Initial credit", Amount(Currency.EUR, Some(100)))
      cardId           <- CardOps.open("Tobia", ledgerId)
      lockIdMaybe      <- CardOps.authAmount(cardId, "First auth", Amount(Currency.EUR, Some(80)))
      lockFailingMaybe <- CardOps.authAmount(cardId, "Failing auth", Amount(Currency.EUR, Some(30)))
      result2          <- lockIdMaybe.map(lock => CardOps.authSettlement(cardId, lock)).getOrElse(UIO.succeed(false))
      ledger           <- ledgerEntity.getLedger
    } yield assertTrue(lockIdMaybe.isDefined && result2) && assertTrue(lockFailingMaybe.isEmpty) && assertTrue(
      ledger.available == Map.empty[Currency, BigDecimal]
    )
  }

  private val canExpireAuth = testM("Can use authorization transactions that expires") {
    val ledgerId = LedgerId(Some(UUID.randomUUID()))
    for {
      ledgerEntity <- LedgerEntity(ledgerId)
      _            <- ledgerEntity.credit("Initial credit", Amount(Currency.EUR, Some(100)))
      cardId       <- CardOps.open("Tobia", ledgerId)
      lockIdMaybe  <- CardOps.authAmount(cardId, "First auth", Amount(Currency.EUR, Some(80)))
      _            <- TestClock.adjust(10.days)
      lockIdMaybe2 <- CardOps.authAmount(cardId, "Second auth", Amount(Currency.EUR, Some(80)))
      ledger       <- ledgerEntity.getLedger
    } yield assertTrue(lockIdMaybe.isDefined && lockIdMaybe2.isDefined) && assertTrue(ledger.available == Map.empty[Currency, BigDecimal])
  }

  override def spec = (suite("A credit card interaction")(
    canMakeCardTransaction,
    canUseAuth,
    canExpireAuth
  ) @@ TestAspect.timeout(5.seconds)).provideSomeLayer[TestEnvironment](layer.orDie)
}
