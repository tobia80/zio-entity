package zio.entity.core

import zio.{Has, Tag, ZIO}

trait AbstractRuntime {
  type Entity[Algebra, Key, State, Event, Reject]

  //TODO create a version of call that accepts an env
  def keyedEntity[R <: Has[_], Key, Algebra, Event: Tag, State: Tag, Reject: Tag, Result](
    key: Key,
    processor: Entity[Key, Algebra, State, Event, Reject]
  )(
    fn: Algebra => ZIO[R, Reject, Result]
  )(implicit ev1: zio.Has[zio.entity.core.Combinators[State, Event, Reject]] <:< R): ZIO[Any, Reject, Result]

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
