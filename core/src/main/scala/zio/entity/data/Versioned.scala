package zio.entity.data

import zio.entity.core.Combinators
import zio.{Chunk, Has, ZIO}

final case class Versioned[A](version: Long, value: A) {
  def map[B](f: A => B): Versioned[B] = Versioned(version, f(value))
}

trait Invocation[State, Event, Reject] {
  def call(message: Chunk[Byte]): ZIO[Has[Combinators[State, Event, Reject]], Throwable, Chunk[Byte]]
}
