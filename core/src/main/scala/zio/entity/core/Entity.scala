package zio.entity.core

import zio.entity.readside.{KillSwitch, ReadSideParams}
import zio.stream.ZStream
import zio.{Has, IO, Promise, Ref, Tag, Task, UIO, URIO, ZIO}

trait Entity[Key, Algebra, State, Event, Reject] {

  def apply(key: Key): Algebra

  case class TerminateSubscription(task: IO[Reject, Unit])

  // TODO: fix terminate subscription
  def readSideSubscription(
    readSideParams: ReadSideParams[Key, Event, Reject],
    errorHandler: Throwable => Reject
  ): IO[Reject, TerminateSubscription] = for {
    ref        <- Ref.make[Option[KillSwitch]](None)
    killReturn <- Promise.make[Reject, Unit]
    _          <- readSideStream(readSideParams, errorHandler).interruptWhen(killReturn).foreach(el => ref.set(Some(el))).forkDaemon
  } yield TerminateSubscription(ref.get.flatMap {
    case Some(kill) => kill.shutdown.mapError(errorHandler)
    case None       => killReturn.succeed(()).unit
  })

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
