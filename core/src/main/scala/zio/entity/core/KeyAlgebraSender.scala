package zio.entity.core

import scodec.bits.BitVector
import zio.Task
import zio.entity.data.{CommandResult, StemProtocol}

object KeyAlgebraSender {
  def keyToAlgebra[Key, Algebra, State, Event, Reject](senderFn: (Key, BitVector) => Task[Any], errorHandler: Throwable => Reject)(implicit
    protocol: StemProtocol[Algebra, State, Event, Reject]
  ): Key => Algebra = { key: Key =>
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
}
