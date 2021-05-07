package zio.entity.core

import zio.duration.durationInt
import zio.entity.core.Combinators._
import zio.entity.core.CounterEntity._
import zio.entity.core.Fold.impossible
import zio.entity.core.journal.MemoryEventJournal
import zio.entity.core.snapshot.Snapshotting
import zio.entity.data.Tagging.Const
import zio.entity.data.{EventTag, StemProtocol, Tagging}
import zio.entity.macros.RpcMacro
import zio.entity.macros.annotations.MethodId
import zio.entity.test.TestEntityRuntime._
import zio.test.Assertion.equalTo
import zio.test.environment.TestEnvironment
import zio.test.{assert, DefaultRunnableSpec, ZSpec}
import zio.{UIO, ZLayer}

object LocalRuntimeWithProtoSpec extends DefaultRunnableSpec {

  private val layer = ZLayer.succeed(Snapshotting.disabled[String, Int]) and
    MemoryEventJournal.make[String, CountEvent](300.millis).toLayer to
    testEntity(CounterEntity.tagging, EventSourcedBehaviour(new CounterCommandHandler, CounterEntity.eventHandlerLogic, _.getMessage))

  override def spec: ZSpec[TestEnvironment, Any] = suite("An entity built with LocalRuntimeWithProto")(
    testM("receives commands, produces events and updates state") {
      (for {
        (counter, probe) <- testEntityWithProbes[String, CounterCommandHandler, Int, CountEvent, String]
        res <- counter("key")(
          _.increase(3)
        )
        finalRes <- counter("key")(
          _.decrease(2)
        )
        events <- probe("key").events
        fromState <- counter("key")(
          _.getValue
        )
      } yield {
        assert(events)(equalTo(List(CountIncremented(3), CountDecremented(2)))) &&
        assert(res)(equalTo(3)) &&
        assert(finalRes)(equalTo(1)) &&
        assert(fromState)(equalTo(1))
      }).provideSomeLayer[TestEnvironment](layer)
    }
  )
}

sealed trait CountEvent
case class CountIncremented(number: Int) extends CountEvent
case class CountDecremented(number: Int) extends CountEvent

class CounterCommandHandler {
  type EIO[Result] = Combinators.EIO[Int, CountEvent, String, Result]

  @MethodId(1)
  def increase(number: Int): EIO[Int] = combinators { c =>
    c.read flatMap { res =>
      c.append(CountIncremented(number)).as(res + number)
    }
  }

  @MethodId(2)
  def decrease(number: Int): EIO[Int] = combinators { c =>
    c.read flatMap { res =>
      c.append(CountDecremented(number)).as(res - number)
    }
  }

  @MethodId(3)
  def noop: EIO[Unit] = combinators(_.ignore)

  @MethodId(4)
  def getValue: EIO[Int] = combinators(_.read)
}

object CounterEntity {
  type Counters = String => CounterCommandHandler

  val tagging: Const[String] = Tagging.const[String](EventTag("Counter"))

  val eventHandlerLogic: Fold[Int, CountEvent] = Fold(
    initial = 0,
    reduce = {
      case (state, CountIncremented(number)) => UIO.succeed(state + number)
      case (state, CountDecremented(number)) => UIO.succeed(state - number)
      case _                                 => impossible
    }
  )

  implicit val counterProtocol: StemProtocol[CounterCommandHandler, Int, CountEvent, String] =
    RpcMacro.derive[CounterCommandHandler, Int, CountEvent, String]

  val live = LocalRuntimeWithProtocol
    .memory[String, CounterCommandHandler, Int, CountEvent, String](
      tagging,
      EventSourcedBehaviour(new CounterCommandHandler(), eventHandlerLogic, _.getMessage)
    )
    .toLayer
}
