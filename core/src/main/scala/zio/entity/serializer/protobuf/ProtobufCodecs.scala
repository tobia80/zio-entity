package zio.entity.serializer.protobuf

import scalapb.{GeneratedMessage, GeneratedMessageCompanion, GeneratedSealedOneof, TypeMapper}
import zio.Chunk
import zio.entity.serializer.SchemaCodec

import scala.util.Try

object ProtobufCodecs {
  implicit def codec[T <: GeneratedMessage: GeneratedMessageCompanion]: SchemaCodec[T] = new SchemaCodec[T] {
    override def encode(t: T): Chunk[Byte] = Chunk.fromArray(t.toByteArray)

    override def decode(bytes: Chunk[Byte]): Try[T] = Try(implicitly[GeneratedMessageCompanion[T]].parseFrom(bytes.toArray))
  }

  implicit def codecSealed[T <: GeneratedSealedOneof, L <: GeneratedMessage: GeneratedMessageCompanion](implicit typeMapper: TypeMapper[L, T]): SchemaCodec[T] =
    new SchemaCodec[T] {
      override def decode(bytes: Chunk[Byte]): Try[T] = Try(
        implicitly[TypeMapper[L, T]].toCustom(implicitly[GeneratedMessageCompanion[L]].parseFrom(bytes.toArray))
      )

      override def encode(t: T): Chunk[Byte] = Chunk.fromArray(implicitly[GeneratedMessageCompanion[L]].toByteArray(implicitly[TypeMapper[L, T]].toBase(t)))
    }

}
