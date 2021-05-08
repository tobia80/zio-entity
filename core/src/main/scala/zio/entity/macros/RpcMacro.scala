package zio.entity.macros

import zio.entity.data.EntityProtocol

import scala.language.experimental.macros

object RpcMacro {

  def derive[Algebra, State, Event, Reject]: EntityProtocol[Algebra, State, Event, Reject] = macro DeriveMacros.derive[Algebra, State, Event, Reject]

}
