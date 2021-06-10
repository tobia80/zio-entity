package zio.entity.core

import zio.entity.data.{CommandResult, EntityProtocol}
import zio.entity.readside.{KillSwitch, ReadSideParams}
import zio.stream.ZStream
import zio.{Chunk, Has, Tag, Task, ZIO}

object KeyAlgebraSender {
  def keyToAlgebra[Key, Algebra, State, Event, Reject](
    fnStream: (
      ReadSideParams[Key, Event, Reject],
      Throwable => Reject
    ) => ZStream[Any, Reject, KillSwitch]
  )(senderFn: (Key, Chunk[Byte]) => Task[Any], errorHandler: Throwable => Reject)(implicit
    protocol: EntityProtocol[Algebra, Reject],
    stateTag: Tag[State],
    eventTag: Tag[Event],
    rejectTag: Tag[Reject]
  ): Entity[Key, Algebra, State, Event, Reject] = {

    new Entity[Key, Algebra, State, Event, Reject] {
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

      override def apply(
        key: Key
      ): Algebra = fn(key)

      override def readSideStream(
        readSideParams: ReadSideParams[Key, Event, Reject],
        errorHandler: Throwable => Reject
      ): ZStream[Any, Reject, KillSwitch] = fnStream(readSideParams, errorHandler)
    }

  }
}
