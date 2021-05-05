package zio.entity.macros

import zio.entity.data.StemProtocol

import scala.language.experimental.macros

object RpcMacro {

  def derive[Algebra, State, Event, Reject]: StemProtocol[Algebra, State, Event, Reject] = macro DeriveMacros.derive[Algebra, State, Event, Reject]

}
