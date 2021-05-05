package zio.entity.core

import zio.UIO
import zio.entity.core.Combinators._
import zio.entity.core.Fold.impossible
import zio.entity.data.{EventTag, StemProtocol, Tagging}
import zio.entity.macros.RpcMacro
import zio.entity.macros.annotations.MethodId
import zio.test.environment.TestEnvironment
import zio.test.{DefaultRunnableSpec, ZSpec}

object LocalRuntimeWithProtoSpec extends DefaultRunnableSpec {
  override def spec: ZSpec[TestEnvironment, Any] = ???
}

sealed trait CountEvent
case class CountIncremented(number: Int) extends CountEvent
case class CountDecremented(number: Int) extends CountEvent

class CounterCommandHandler {
  type EIO[Result] = Combinators.EIO[Int, CountEvent, String, Result]

  @MethodId(1)
  def increase(number: Int): EIO[Int] = read[Int, CountEvent, String]
    .tap { _ =>
      append(CountIncremented(number))
    }

  @MethodId(2)
  def decrease(number: Int): EIO[Int] =
    read[Int, CountEvent, String] tap { _ =>
      append(CountIncremented(number))
    }

  @MethodId(3)
  def noop: EIO[Unit] = ignore
}

object CounterEntity {
  type Counters = String => CounterCommandHandler

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

  val tagging: Tagging.Const[Any] = Tagging.const(EventTag("Counter"))

  val live = LocalRuntimeWithProtocol
    .memory[String, CounterCommandHandler, Int, CountEvent, String](
      tagging,
      EventSourcedBehaviour(new CounterCommandHandler(), eventHandlerLogic, _.getMessage)
    )
    .toLayer
}
