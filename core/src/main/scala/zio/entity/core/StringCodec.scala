package zio.entity.core

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
