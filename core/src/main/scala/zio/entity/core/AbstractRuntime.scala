package zio.entity.core

import zio.{Has, Tag, ZIO}

trait AbstractRuntime {
  type Entity[Algebra, Key, State, Event, Reject]

  def call[R <: Has[_], Algebra, Key, Event: Tag, State: Tag, Reject: Tag, Result](key: Key, processor: Entity[Algebra, Key, State, Event, Reject])(
    fn: Algebra => ZIO[R with Has[Combinators[State, Event, Reject]], Reject, Result]
  ): ZIO[R, Reject, Result]
}

trait StringDecoder[A] {
  def apply(key: String): Option[A]

  final def decode(key: String): Option[A] = apply(key)
}

object StringDecoder {
  def apply[A: StringDecoder]: StringDecoder[A] = implicitly[StringDecoder[A]]

  implicit val stringDecoder: StringDecoder[String] = (a: String) => Some(a)
}

trait StringEncoder[A] {
  def apply(a: A): String

  final def encode(a: A): String = apply(a)
}

object StringEncoder {
  def apply[A: StringEncoder]: StringEncoder[A] = implicitly[StringEncoder[A]]

  implicit val stringEncoder: StringEncoder[String] = (a: String) => a
}
