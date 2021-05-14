package zio.entity.serializer.protobuf

import scalapb.{GeneratedMessage, GeneratedMessageCompanion}
import zio.Chunk
import zio.entity.serializer.SchemaCodec

import scala.util.Try

object ProtobufCodecs {
  implicit def codec[T <: GeneratedMessage: GeneratedMessageCompanion]: SchemaCodec[T] = new SchemaCodec[T] {
    override def encode(t: T): Chunk[Byte] = Chunk.fromArray(t.toByteArray)

    override def decode(bytes: Chunk[Byte]): Try[T] = Try(implicitly[GeneratedMessageCompanion[T]].parseFrom(bytes.toArray))
  }
}
