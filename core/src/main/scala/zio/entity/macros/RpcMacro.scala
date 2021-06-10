package zio.entity.macros

import zio.entity.data.EntityProtocol

import scala.language.experimental.macros

object RpcMacro {

  def derive[Algebra, Reject]: EntityProtocol[Algebra, Reject] = macro DeriveMacros.derive[Algebra, Reject]

}
