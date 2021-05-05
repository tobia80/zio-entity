package zio.entity.data

import scodec.bits.BitVector

sealed trait EntityCommand
case class CommandInvocation(bytes: BitVector) extends EntityCommand

case class CommandResult(bytes: BitVector)
