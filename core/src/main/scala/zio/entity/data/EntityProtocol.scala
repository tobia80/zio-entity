package zio.entity.data

import zio.{Chunk, Task}

trait EntityProtocol[Algebra, Reject] {

  val client: (Chunk[Byte] => Task[Chunk[Byte]], Throwable => Reject) => Algebra

  val server: (Algebra, Throwable => Reject) => Invocation

}
