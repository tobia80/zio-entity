package zio.entity.core

import zio.{Has, ZIO}

trait EntityBase[Key, Algebra, State, Event, Reject] {
  def apply[R <: Has[_], Result](key: Key)(f: Algebra => ZIO[R, Reject, Result])(implicit
    ev1: zio.Has[zio.entity.core.Combinators[State, Event, Reject]] <:< R
  ): ZIO[Any, Reject, Result]

}
