package zio.entity.data

import scodec.bits.BitVector
import zio.entity.core.Combinators
import zio.{Has, Task, ZIO}

final case class Versioned[A](version: Long, value: A) {
  def map[B](f: A => B): Versioned[B] = Versioned(version, f(value))
}

trait Invocation[State, Event, Reject] {
  def call(message: BitVector): ZIO[Has[Combinators[State, Event, Reject]], Throwable, BitVector]
}
