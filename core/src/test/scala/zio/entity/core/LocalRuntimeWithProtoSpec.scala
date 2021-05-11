package zio.entity.core

import zio.clock.Clock
import zio.entity.core.Combinators._
import zio.entity.core.CounterCommandHandler.EIO
import zio.entity.core.Fold.impossible
import zio.entity.data.Tagging.Const
import zio.entity.data.{ConsumerId, EntityProtocol, EventTag, Tagging}
import zio.entity.macros.RpcMacro
import zio.entity.macros.annotations.MethodId
import zio.entity.readside.ReadSideParams
import zio.entity.test.TestEntityRuntime._
import zio.entity.test.TestMemoryStores
import zio.test.Assertion.equalTo
import zio.test.environment.TestEnvironment
import zio.test.{assert, DefaultRunnableSpec, ZSpec}
import zio.{IO, Ref, UIO}

object LocalRuntimeWithProtoSpec extends DefaultRunnableSpec {

  private val counterCommandHandler: Counter = CounterCommandHandler
  import CounterEntity.counterProtocol
  private val layer = Clock.any and TestMemoryStores.live[String, CountEvent, Int]() to
    testEntity(CounterEntity.tagging, EventSourcedBehaviour(counterCommandHandler, CounterEntity.eventHandlerLogic, _.getMessage))

  override def spec: ZSpec[TestEnvironment, Any] = suite("An entity built with LocalRuntimeWithProto")(
    testM("receives commands, produces events and updates state") {
      (for {
        counter              <- testEntityWithProbe[String, Counter, Int, CountEvent, String]
        res                  <- counter("key")(_.increase(3))
        finalRes             <- counter("key")(_.decrease(2))
        secondEntityRes      <- counter("secondKey")(_.increase(1))
        secondEntityFinalRes <- counter("secondKey")(_.increase(5))
        events               <- counter.probeForKey("key").events
        fromState            <- counter("key")(_.getValue)
      } yield {
        assert(events)(equalTo(List(CountIncremented(3), CountDecremented(2)))) &&
        assert(res)(equalTo(3)) &&
        assert(finalRes)(equalTo(1)) &&
        assert(secondEntityRes)(equalTo(1)) &&
        assert(secondEntityFinalRes)(equalTo(6)) &&
        assert(fromState)(equalTo(1))
      }).provideSomeLayer[TestEnvironment](layer)
    },
    testM("Read side processing processes work") {
      (for {
        counter <- testEntityWithProbe[String, Counter, Int, CountEvent, String]
        state   <- Ref.make(0)
        killSwitch <- counter
          .readSideSubscription(ReadSideParams("read", ConsumerId("1"), CounterEntity.tagging, 2, ReadSide.countIncreaseEvents(state, _, _)), _.getMessage)
        _            <- counter("key")(_.increase(2))
        _            <- counter("key")(_.increase(3))
        _            <- counter("key")(_.decrease(1))
        _            <- counter.triggerReadSideProcessing(1)
        valueOfState <- state.get
      } yield (assert(valueOfState)(equalTo(2)))).provideSomeLayer[TestEnvironment](layer)
    }
  )
}

sealed trait CountEvent
case class CountIncremented(number: Int) extends CountEvent
case class CountDecremented(number: Int) extends CountEvent

trait Counter {
  @MethodId(1)
  def increase(number: Int): EIO[Int]

  @MethodId(2)
  def decrease(number: Int): EIO[Int]

  @MethodId(3)
  def noop: EIO[Unit]

  @MethodId(4)
  def getValue: EIO[Int]
}

object CounterCommandHandler extends Counter {
  type EIO[Result] = Combinators.EIO[Int, CountEvent, String, Result]

  def increase(number: Int): EIO[Int] = combinators { c =>
    c.read flatMap { res =>
      c.append(CountIncremented(number)).as(res + number)
    }
  }

  def decrease(number: Int): EIO[Int] = combinators { c =>
    c.read flatMap { res =>
      c.append(CountDecremented(number)).as(res - number)
    }
  }

  def noop: EIO[Unit] = combinators(_.ignore)

  def getValue: EIO[Int] = combinators(_.read)
}

object CounterEntity {
  type Counters = String => Counter

  val tagging: Const[String] = Tagging.const[String](EventTag("Counter"))

  val eventHandlerLogic: Fold[Int, CountEvent] = Fold(
    initial = 0,
    reduce = {
      case (state, CountIncremented(number)) => UIO.succeed(state + number)
      case (state, CountDecremented(number)) => UIO.succeed(state - number)
      case _                                 => impossible
    }
  )

  implicit val counterProtocol: EntityProtocol[Counter, Int, CountEvent, String] =
    RpcMacro.derive[Counter, Int, CountEvent, String]

}

// many read side but we need only one stream
object ReadSide {

  def countIncreaseEvents(state: Ref[Int], id: String, countEvent: CountEvent): IO[String, Unit] =
    countEvent match {
      case CountIncremented(_) => state.update(_ + 1)
      case _                   => UIO.unit
    }

}
