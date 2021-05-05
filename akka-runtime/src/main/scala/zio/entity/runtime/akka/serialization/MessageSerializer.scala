package zio.entity.runtime.akka.serialization

import akka.actor.ExtendedActorSystem
import akka.serialization.{BaseSerializer, SerializerWithStringManifest}
import com.google.protobuf.ByteString
import scodec.bits.BitVector
import zio.entity.data.{CommandInvocation, CommandResult}
import zio.entity.runtime.akka.Runtime.KeyedCommand

import scala.collection.immutable.HashMap

class MessageSerializer(val system: ExtendedActorSystem) extends SerializerWithStringManifest with BaseSerializer {

  val KeyedCommandManifest = "A"
  val CommandManifest = "B"
  val CommandResultManifest = "C"

  private val fromBinaryMap =
    HashMap[String, Array[Byte] => AnyRef](
      KeyedCommandManifest  -> keyedCommandFromBinary,
      CommandManifest       -> commandFromBinary,
      CommandResultManifest -> commandResultFromBinary
    )

  override def manifest(o: AnyRef): String = o match {
    case KeyedCommand(_, _)   => KeyedCommandManifest
    case CommandInvocation(_) => CommandManifest
    case CommandResult(_)     => CommandResultManifest
    case x                    => throw new IllegalArgumentException(s"Serialization of [$x] is not supported")
  }

  override def toBinary(o: AnyRef): Array[Byte] = o match {
    case CommandInvocation(bytes) =>
      bytes.toByteArray
    case CommandResult(bytes) =>
      bytes.toByteArray
    case x @ KeyedCommand(_, _) =>
      entityCommandToBinary(x)
    case x => throw new IllegalArgumentException(s"Serialization of [$x] is not supported")
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    fromBinaryMap.get(manifest) match {
      case Some(f) => f(bytes)
      case other   => throw new IllegalArgumentException(s"Unknown manifest [$other]")
    }

  private def entityCommandToBinary(a: KeyedCommand): Array[Byte] =
    msg.runtime.KeyedCommand(a.key, ByteString.copyFrom(a.bytes.toByteBuffer)).toByteArray

  private def keyedCommandFromBinary(bytes: Array[Byte]): KeyedCommand =
    msg.runtime.KeyedCommand.parseFrom(bytes) match {
      case msg.runtime.KeyedCommand(key, commandBytes, _) =>
        KeyedCommand(key, BitVector(commandBytes.asReadOnlyByteBuffer()))
    }

  private def commandFromBinary(bytes: Array[Byte]): CommandInvocation =
    CommandInvocation(BitVector(bytes))

  private def commandResultFromBinary(bytes: Array[Byte]): CommandResult =
    CommandResult(BitVector(bytes))
}
