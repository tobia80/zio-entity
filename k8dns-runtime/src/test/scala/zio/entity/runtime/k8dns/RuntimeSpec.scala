package zio.entity.runtime.k8dns

import zio.clock.Clock
import zio.duration.durationInt
import zio.entity.annotations.MethodId
import zio.entity.core.Combinators.combinators
import zio.entity.core.Entity.entity
import zio.entity.core.Fold.impossible
import zio.entity.core._
import zio.entity.data.Tagging.Const
import zio.entity.data.{ConsumerId, EntityProtocol, EventTag, Tagging}
import zio.entity.macros.RpcMacro
import zio.entity.readside.ReadSideParams
import zio.memberlist.Memberlist.SwimEnv
import zio.test.Assertion.equalTo
import zio.test.TestAspect.sequential
import zio.test.environment.TestEnvironment
import zio.test.{assert, DefaultRunnableSpec, ZSpec}
import zio.{Has, IO, UIO, ZEnv, ZLayer}

class RuntimeSpec extends DefaultRunnableSpec {
  import CounterEntity.counterProtocol
  private val stores: ZLayer[Any, Nothing, Has[Stores[String, CountEvent, Int]]] = Clock.live to MemoryStores.live[String, CountEvent, Int](100.millis, 2)

  private val swimEnv: ZLayer[Any, Nothing, SwimEnv] = ???
  private val runtimeServer: ZLayer[SwimEnv, Throwable, Has[RuntimeServer]] =
    SwimRuntimeServer.live(5.seconds, 5.seconds, 3.seconds)

  private val layer: ZLayer[ZEnv, Throwable, Has[Entity[String, Counter, Int, CountEvent, String]]] =
    Clock.any and stores and (swimEnv to runtimeServer) to Runtime
      .entityLive(
        "Counter",
        CounterEntity.tagging,
        EventSourcedBehaviour[Counter, Int, CountEvent, String](new CounterCommandHandler(_), CounterEntity.eventHandlerLogic, _.getMessage)
      )
      .toLayer

  override def spec: ZSpec[TestEnvironment, Any] = suite("A zio native runtime")(
    testM("receives commands and updates state") {
      (for {
        counter              <- entity[String, Counter, Int, CountEvent, String]
        res                  <- counter("key").increase(3)
        finalRes             <- counter("key").decrease(2)
        secondEntityRes      <- counter("secondKey").increase(1)
        secondEntityFinalRes <- counter("secondKey").increase(5)
        fromState            <- counter("key").getValue
      } yield {
        assert(res)(equalTo(3)) &&
        assert(finalRes)(equalTo(1)) &&
        assert(secondEntityRes)(equalTo(1)) &&
        assert(secondEntityFinalRes)(equalTo(6)) &&
        assert(fromState)(equalTo(1))
      }).provideSomeLayer[TestEnvironment](layer)
    },
    testM("Read side processing processes work") {
      (for {
        counter <- entity[String, Counter, Int, CountEvent, String]
        promise <- zio.Promise.make[Nothing, Int]
        killSwitch <- counter
          .readSideSubscription(ReadSideParams("read", ConsumerId("1"), CounterEntity.tagging, 2, ReadSide.countIncreaseEvents(promise, _, _)), _.getMessage)
        _      <- counter("key").increase(2)
        _      <- counter("key").decrease(1)
        result <- promise.await
      } yield (assert(result)(equalTo(2)))).provideSomeLayer[TestEnvironment](Clock.live and layer)
    }
  ) @@ sequential
}

sealed trait CountEvent
case class CountIncremented(number: Int) extends CountEvent
case class CountDecremented(number: Int) extends CountEvent

trait Counter {
  @MethodId(1)
  def increase(number: Int): IO[String, Int]

  @MethodId(2)
  def decrease(number: Int): IO[String, Int]

  @MethodId(3)
  def noop: IO[String, Unit]

  @MethodId(4)
  def getValue: IO[String, Int]
}

class CounterCommandHandler(combinators: Combinators[Int, CountEvent, String]) extends Counter {
  import combinators._

  def increase(number: Int): IO[String, Int] = read flatMap { res =>
    append(CountIncremented(number)).as(res + number)
  }

  def decrease(number: Int): IO[String, Int] = read flatMap { res =>
    append(CountDecremented(number)).as(res - number)
  }

  def noop: IO[String, Unit] = ignore

  def getValue: IO[String, Int] = read
}

object CounterEntity {

  val tagging: Const[String] = Tagging.const[String](EventTag("Counter"))

  val eventHandlerLogic: Fold[Int, CountEvent] = Fold(
    initial = 0,
    reduce = {
      case (state, CountIncremented(number)) => UIO.succeed(state + number)
      case (state, CountDecremented(number)) => UIO.succeed(state - number)
      case _                                 => impossible
    }
  )

  implicit val counterProtocol: EntityProtocol[Counter, String] =
    RpcMacro.derive[Counter, String]

}

object ReadSide {

  def countIncreaseEvents(promise: zio.Promise[Nothing, Int], id: String, countEvent: CountEvent): IO[String, Unit] =
    countEvent match {
      case CountIncremented(value) =>
        promise.succeed(value).unit
      case _ => UIO.unit
    }

}
