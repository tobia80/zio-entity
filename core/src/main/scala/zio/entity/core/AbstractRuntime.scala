package zio.entity.core

import zio.{Has, Tag, ZIO}

trait AbstractRuntime {
  type Entity[Algebra, Key, State, Event, Reject]

  def call[R <: Has[_], Algebra, Key, Event: Tag, State: Tag, Reject: Tag, Result](key: Key, processor: Entity[Algebra, Key, State, Event, Reject])(
    fn: Algebra => ZIO[R with Has[Combinators[State, Event, Reject]], Reject, Result]
  ): ZIO[R, Reject, Result]
}

trait KeyDecoder[A] {
  def apply(key: String): Option[A]

  final def decode(key: String): Option[A] = apply(key)
}

object KeyDecoder {
  def apply[A: KeyDecoder]: KeyDecoder[A] = implicitly[KeyDecoder[A]]

  implicit val stringKeyDecoder: KeyDecoder[String] = (a: String) => Some(a)
}

trait KeyEncoder[A] {
  def apply(a: A): String

  final def encode(a: A): String = apply(a)
}

object KeyEncoder {
  def apply[A: KeyEncoder]: KeyEncoder[A] = implicitly[KeyEncoder[A]]

  implicit val stringKeyEncoder: KeyEncoder[String] = (a: String) => a
}
