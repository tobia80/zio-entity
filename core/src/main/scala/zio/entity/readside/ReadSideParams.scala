package zio.entity.readside

import zio.IO
import zio.entity.data.{ConsumerId, Tagging}

case class ReadSideParams[Id, Event, Reject](
  name: String,
  consumerId: ConsumerId,
  tagging: Tagging[Id],
  parallelism: Int = 30,
  logic: (Id, Event) => IO[Reject, Unit]
)
