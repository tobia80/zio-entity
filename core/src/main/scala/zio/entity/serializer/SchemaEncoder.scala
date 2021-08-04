package zio.entity.serializer

import zio.Chunk

import scala.util.Try

//TODO make it compatible with zio-schema (look at Transform)
object SchemaEncoder {
  def apply[T: SchemaEncoder]: SchemaEncoder[T] = implicitly[SchemaEncoder[T]]
}

trait SchemaEncoder[T] {

  def encode(t: T): Chunk[Byte]

}

object SchemaDecoder {
  def apply[T: SchemaDecoder]: SchemaDecoder[T] = implicitly[SchemaDecoder[T]]
}

trait SchemaDecoder[T] {

  def decode(bytes: Chunk[Byte]): Try[T]

}

trait SchemaCodec[T] extends SchemaEncoder[T] with SchemaDecoder[T]

object SchemaCodec {
  def apply[T: SchemaCodec]: SchemaCodec[T] = implicitly[SchemaCodec[T]]
}
