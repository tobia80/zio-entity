package zio.entity.data

import zio.{Chunk, Task}

final case class Versioned[A](version: Long, value: A) {
  def map[B](f: A => B): Versioned[B] = Versioned(version, f(value))
}

trait Invocation {
  def call(message: Chunk[Byte]): Task[Chunk[Byte]]
}
