package zio.entity.macros

import _root_.boopickle.Default._
import boopickle.Pickler
import scalapb.{GeneratedMessage, GeneratedMessageCompanion}
import zio.{Chunk, Task}

import java.nio.ByteBuffer

object BoopickleCodec {
  def encoder[A](implicit pickler: Pickler[A]): Encoder[A] = (value: A) => {
    val byteArray = Pickle.intoBytes(value)
    Task.effect(Chunk.fromByteBuffer(byteArray))
  }
  def decoder[A](implicit pickler: Pickler[A]): Decoder[A] =
    (bits: Chunk[Byte]) =>
      Task.fromTry(
        Unpickle
          .apply[A]
          .tryFromBytes(ByteBuffer.wrap(bits.toArray))
      )

  def codec[A](implicit pickler: Pickler[A]): Codec[A] = Codec(encoder[A], decoder[A])

  implicit val chunkPickler: Pickler[Chunk[Byte]] = new Pickler[Chunk[Byte]] {
    override def pickle(obj: Chunk[Byte])(implicit state: PickleState): Unit = {
      state.enc.writeByteArray(obj.toArray)
    }
    override def unpickle(implicit state: UnpickleState): Chunk[Byte] = {
      Chunk.fromArray(state.dec.readByteArray())
    }
  }

  implicit def protobufPickler[A <: GeneratedMessage: GeneratedMessageCompanion]: Pickler[A] = new Pickler[A] {
    override def pickle(obj: A)(implicit state: PickleState): Unit = state.enc.writeByteArray(implicitly[GeneratedMessageCompanion[A]].toByteArray(obj))

    override def unpickle(implicit state: UnpickleState): A = implicitly[GeneratedMessageCompanion[A]].parseFrom(state.dec.readByteArray())
  }
}

trait Encoder[-A] {
  def encode(value: A): Task[Chunk[Byte]]
}

trait Decoder[+A] {
  def decode(value: Chunk[Byte]): Task[A]
}

trait Codec[A] extends Encoder[A] with Decoder[A]

object Codec {
  def apply[A](encoder: Encoder[A], decoder: Decoder[A]): Codec[A] = new Codec[A] {
    override def decode(value: Chunk[Byte]): Task[A] = decoder.decode(value)

    override def encode(value: A): Task[Chunk[Byte]] = encoder.encode(value)
  }
}
