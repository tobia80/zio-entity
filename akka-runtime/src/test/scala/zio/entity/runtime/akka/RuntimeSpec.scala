package zio.entity.runtime.akka

import zio.UIO
import zio.duration.durationInt
import zio.entity.core.Combinators.combinators
import zio.entity.core.Fold.impossible
import zio.entity.core.journal.{EventJournal, MemoryEventJournal}
import zio.entity.core.{Combinators, EventSourcedBehaviour, Fold}
import zio.entity.data.Tagging.Const
import zio.entity.data.{EventTag, StemProtocol, Tagging}
import zio.entity.macros.RpcMacro
import zio.entity.macros.annotations.MethodId
import zio.entity.runtime.akka.CounterEntity._
import zio.entity.test.TestEntityRuntime.entity
import zio.test.Assertion.equalTo
import zio.test.environment.TestEnvironment
import zio.test.{assert, DefaultRunnableSpec, ZSpec}

object RuntimeSpec extends DefaultRunnableSpec {

  private val layer = (Runtime.actorSettings("Test") and
    MemoryEventJournal.make[String, CountEvent](300.millis).toLayer[EventJournal[String, CountEvent]]) to
    Runtime.memory("Counter", CounterEntity.tagging, EventSourcedBehaviour(new CounterCommandHandler, CounterEntity.eventHandlerLogic, _.getMessage)).toLayer

  override def spec: ZSpec[TestEnvironment, Any] = suite("An entity built with Akka Runtime")(
    testM("receives commands and updates state") {
      (for {
        counter <- entity[String, CounterCommandHandler, Int, CountEvent, String]
        res <- counter("key")(
          _.increase(3)
        )
        finalRes <- counter("key")(
          _.decrease(2)
        )
        secondEntityRes <- counter("secondKey") {
          _.increase(1)
        }
        secondEntityFinalRes <- counter("secondKey") {
          _.increase(5)
        }
        fromState <- counter("key")(
          _.getValue
        )
      } yield {
        assert(res)(equalTo(3)) &&
        assert(finalRes)(equalTo(1)) &&
        assert(secondEntityRes)(equalTo(1)) &&
        assert(secondEntityFinalRes)(equalTo(6)) &&
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

}
