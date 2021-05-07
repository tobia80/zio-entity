package zio.entity.core

import scodec.bits.BitVector
import zio.{Has, Tag, Task, ZIO}
import zio.entity.data.{CommandResult, StemProtocol}

object KeyAlgebraSender {
  def keyToAlgebra[Key, Algebra, State, Event, Reject](senderFn: (Key, BitVector) => Task[Any], errorHandler: Throwable => Reject)(implicit
    protocol: StemProtocol[Algebra, State, Event, Reject],
    stateTag: Tag[State],
    eventTag: Tag[Event],
    rejectTag: Tag[Reject]
  ): EntityBase[Key, Algebra, State, Event, Reject] = {

    new EntityBase[Key, Algebra, State, Event, Reject] {
      val fn: Key => Algebra = { key: Key =>
        {
          // implementation of algebra that transform the method in bytes inject the function in it
          protocol.client(
            { bytes =>
              senderFn(key, bytes)
                .flatMap {
                  case result: CommandResult =>
                    Task.succeed(result.bytes)
                  case other =>
                    Task.fail(
                      new IllegalArgumentException(s"Unexpected response [$other] from shard region")
                    )
                }
            },
            errorHandler
          )
        }
      }

      override def apply[R <: Has[_], Result](
        key: Key
      )(f: Algebra => ZIO[R, Reject, Result])(implicit ev1: Has[Combinators[State, Event, Reject]] <:< R): ZIO[Any, Reject, Result] = {
        val algebra = fn(key)
        f(algebra).provideLayer(Combinators.clientEmptyCombinator[State, Event, Reject])
      }
    }

  }
}
