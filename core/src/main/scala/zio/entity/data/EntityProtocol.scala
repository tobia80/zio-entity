package zio.entity.data

import scodec.bits.BitVector
import zio.Task

trait EntityProtocol[Algebra, State, Event, Reject] {

  //TODO replace BitVector with T
  val client: (BitVector => Task[BitVector], Throwable => Reject) => Algebra

  val server: (Algebra, Throwable => Reject) => Invocation[State, Event, Reject]

}
