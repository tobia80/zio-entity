package zio.entity.data

import zio.Chunk

sealed trait EntityCommand
case class CommandInvocation(bytes: Chunk[Byte]) extends EntityCommand

case class CommandResult(bytes: Chunk[Byte])
