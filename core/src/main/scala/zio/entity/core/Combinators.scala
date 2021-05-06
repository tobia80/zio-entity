package zio.entity.core

import zio._
import zio.entity.data.EntityEvent

trait Combinators[+State, -Event, Reject] {
  def read: IO[Reject, State]
  def append(es: Event, other: Event*): IO[Reject, Unit]
  def ignore: UIO[Unit] = IO.unit
  def reject[A](r: Reject): IO[Reject, A]
}

object Combinators {

  type EIO[State, Event, Reject, Result] = ZIO[Has[Combinators[State, Event, Reject]], Reject, Result]
  type ETask[State, Event, Result] = ZIO[Has[Combinators[State, Event, Throwable]], Throwable, Result]

  //TODO: use same technique in ZIO to avoid repeating type params... new ZIO.provideSomeLayer...
  def read[State: Tag, Event: Tag, Reject: Tag]: ZIO[Has[Combinators[State, Event, Reject]], Reject, State] =
    ZIO.accessM[Has[Combinators[State, Event, Reject]]](_.get.read)

  def append[State: Tag, Event: Tag, Reject: Tag](es: Event, other: Event*): ZIO[Has[Combinators[State, Event, Reject]], Reject, Unit] =
    ZIO.accessM[Has[Combinators[State, Event, Reject]]](_.get.append(es, other: _*))

  def ignore[State: Tag, Event: Tag, Reject: Tag]: ZIO[Has[Combinators[State, Event, Reject]], Nothing, Unit] =
    ZIO.accessM[Has[Combinators[State, Event, Reject]]](_.get.ignore)

  def reject[State: Tag, Event: Tag, Reject: Tag, A](r: Reject): ZIO[Has[Combinators[State, Event, Reject]], Reject, A] =
    ZIO.accessM[Has[Combinators[State, Event, Reject]]](_.get.reject(r))

  case class ImpossibleTransitionException[Key, Event, State](state: State, eventPayload: EntityEvent[Key, Event])
      extends RuntimeException(s"Impossible transition from state $state with event $eventPayload")

  def clientEmptyCombinator[State: Tag, Event: Tag, Reject: Tag]: ULayer[Has[Combinators[State, Event, Reject]]] =
    ZLayer.succeed(new Combinators[Nothing, Any, Reject] {
      override def read: IO[Reject, Nothing] = throw new RuntimeException("This is a stub")

      override def append(es: Any, other: Any*): IO[Reject, Unit] = throw new RuntimeException("This is a stub")

      override def reject[A](r: Reject): IO[Reject, A] = throw new RuntimeException("This is a stub")
    })
}
