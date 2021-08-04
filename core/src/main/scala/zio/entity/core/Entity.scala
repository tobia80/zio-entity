package zio.entity.core

import zio.entity.readside.{KillSwitch, ReadSideParams}
import zio.stream.ZStream
import zio.{Has, IO, Tag, Task, UIO, URIO, ZIO}

trait Entity[Key, Algebra, State, Event, Reject] {

  def apply(key: Key): Algebra

  case class TerminateSubscription(task: IO[Reject, Unit])

  // TODO: fix terminate subscription
  def readSideSubscription(
    readSideParams: ReadSideParams[Key, Event, Reject],
    errorHandler: Throwable => Reject
  ): IO[Reject, TerminateSubscription] = (for {
    killSwitch <- readSideStream(readSideParams, errorHandler)
  } yield TerminateSubscription(killSwitch.shutdown.mapError(errorHandler))).runLast.map(_.getOrElse(TerminateSubscription(UIO.unit)))

  def readSideStream(
    readSideParams: ReadSideParams[Key, Event, Reject],
    errorHandler: Throwable => Reject
  ): ZStream[Any, Reject, KillSwitch]
}

object Entity {
  def entity[Key: Tag, Algebra: Tag, State: Tag, Event: Tag, Reject: Tag]
    : URIO[Has[Entity[Key, Algebra, State, Event, Reject]], Entity[Key, Algebra, State, Event, Reject]] =
    ZIO.service[Entity[Key, Algebra, State, Event, Reject]]
}
