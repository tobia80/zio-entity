package zio.entity.example

import zio.clock.Clock
import zio.duration.durationInt
import zio.entity.core.{Entity, EventSourcedBehaviour}
import zio.entity.example.Amount.Currency
import zio.entity.example.creditcard._
import zio.entity.example.creditcard.readside.ActiveLocksTracker
import zio.entity.example.creditcard.readside.ActiveLocksTracker.{LockKey, LockValue}
import zio.entity.example.ledger.LedgerEntity.LedgerEntity
import zio.entity.example.ledger._
import zio.entity.example.scheduler.{AuthorizationReleaser, FixedPollAuthorizationReleaser, JobRunners}
import zio.entity.example.storage.MemoryExpiringStorage
import zio.entity.test.TestEntityRuntime.testEntity
import zio.entity.test.{TestEntityRuntime, TestMemoryStores}
import zio.test.environment.{TestClock, TestEnvironment}
import zio.test.{assertTrue, DefaultRunnableSpec, TestAspect}
import zio.{Has, UIO, ZIO, ZLayer}

import java.util.UUID

object CardSpec extends DefaultRunnableSpec {
  import CardEntity.cardProtocol
  import LedgerEntity.ledgerProtocol

  private val expirationDuration = 10.seconds
  private val polling = 500.millis
  private val ledger: ZLayer[Clock, Throwable, Has[Entity[LedgerId, Ledger, LedgerState, LedgerEvent, LedgerError]] with Has[
    TestEntityRuntime.TestEntity[LedgerId, Ledger, LedgerState, LedgerEvent, LedgerError]
  ]] =
    Clock.any and (Clock.any to TestMemoryStores.make[LedgerId, LedgerEvent, LedgerState](polling)) to
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
    Clock.any and TestMemoryStores.make[CardId, CardEvent, CardState](polling) to
    testEntity(
      CardEntity.tagging,
      EventSourcedBehaviour[Card, CardState, CardEvent, CardError](new CardCommandHandler(_), CardEntity.eventHandlerLogic, _ => UnknownCardError)
    )
  private val expiringStorage = MemoryExpiringStorage.make[LockKey, LockValue]
  private val lockTracker: ZLayer[Clock, Throwable, Has[ActiveLocksTracker]] = expiringStorage ++ ledger >>> ActiveLocksTracker.live

  private val authReleaser: ZLayer[Clock, Throwable, Has[AuthorizationReleaser]] =
    Clock.any ++ expiringStorage ++ ledger >>> FixedPollAuthorizationReleaser.make(expirationDuration)
  private val layer = ((card ++ ledger) >+> CardOps.live) ++ lockTracker ++ authReleaser

  private val canMakeCardTransaction = testM("Can make card transaction") {
    val ledgerId = LedgerId(Some(UUID.randomUUID()))
    for {
      ledgerEntity  <- LedgerEntity(ledgerId)
      cardId        <- CardOps.open("Tobia", ledgerId)
      _             <- ledgerEntity.credit("Initial credit", Amount(Currency.EUR, Some(100)) /*, Amount(Currency.EUR, Some(100))*/ )
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
      ledger.available == Map[Currency, BigDecimal](Currency.EUR -> BigDecimal(20))
    )
  }

  private val canExpireAuth = testM("Can use authorization transactions that expires") {
    val ledgerId = LedgerId(Some(UUID.randomUUID()))
    (for {
      _            <- JobRunners.startJobs
      ledgerEntity <- ZIO.service[LedgerEntity]
      _            <- ledgerEntity(ledgerId).credit("Initial credit", Amount(Currency.EUR, Some(100)))
      cardId       <- CardOps.open("Tobia", ledgerId)
      lockIdMaybe  <- CardOps.authAmount(cardId, "First auth", Amount(Currency.EUR, Some(80)))
      _            <- TestClock.adjust(expirationDuration)
      lockIdMaybe2 <- CardOps.authAmount(cardId, "Second auth", Amount(Currency.EUR, Some(80)))
      ledger       <- ledgerEntity(ledgerId).getLedger
    } yield assertTrue(lockIdMaybe.isDefined && lockIdMaybe2.isDefined) && assertTrue(
      ledger.available == Map[Currency, BigDecimal](Currency.EUR -> BigDecimal(20))
    ))

  }

  override def spec = suite("A credit card interaction")(
    canMakeCardTransaction,
    canUseAuth,
    canExpireAuth
  ).provideSomeLayer[TestEnvironment](layer.orDie) @@ TestAspect.timeout(500.seconds)
}
