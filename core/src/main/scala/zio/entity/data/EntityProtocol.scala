package zio.entity.data

import zio.{Chunk, Task}

trait EntityProtocol[Algebra, State, Event, Reject] {

  //TODO replace BitVector with T
  val client: (Chunk[Byte] => Task[Chunk[Byte]], Throwable => Reject) => Algebra

  val server: (Algebra, Throwable => Reject) => Invocation[State, Event, Reject]

}
