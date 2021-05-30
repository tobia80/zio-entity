package zio.entity.runtime.k8dns

import izumi.reflect.Tag
import scodec.bits.BitVector
import zio.{Has, IO, Task, ZIO}
import zio.clock.Clock
import zio.entity.core.{Combinators, Entity, EventSourcedBehaviour, KeyAlgebraSender, Stores, StringDecoder, StringEncoder}
import zio.entity.data.{EntityProtocol, Tagging}
import zio.entity.readside.{KillSwitch, ReadSideParams}
import zio.stream.ZStream

object Runtime {

  def entityLive[Key: StringDecoder: StringEncoder: Tag, Algebra, State: Tag, Event: Tag, Reject: Tag](
    typeName: String,
    tagging: Tagging[Key],
    eventSourcedBehaviour: EventSourcedBehaviour[Algebra, State, Event, Reject]
  )(implicit
    protocol: EntityProtocol[Algebra, State, Event, Reject]
  ): ZIO[Clock with Has[RuntimeServer] with Has[Stores[Key, Event, State]], Throwable, Entity[Key, Algebra, State, Event, Reject]] = {
    for {
      runtimeServer <- ZIO.service[RuntimeServer]
    } yield KeyAlgebraSender.keyToAlgebra[Key, Algebra, State, Event, Reject](???)(
      { (key, payload) =>
        val keyString = implicitly[StringEncoder[Key]].encode(key)
        runtimeServer.send(keyString, payload)
      },
      eventSourcedBehaviour.errorHandler
    )

    ???
  }

}

trait RuntimeServer {
  def receive(payload: BitVector): Task[BitVector]

  def send(key: String, payload: BitVector): Task[BitVector]
}
