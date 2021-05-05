package zio.entity.runtime.akka.readside

import akka.actor.ActorSystem
import zio.entity.readside.{KillSwitch, ReadSideProcess, ReadSideProcessing => TReadSideProcessing}
import zio.{Has, ZIO, ZLayer}

object ReadSideProcessing {

  def start(name: String, processes: List[ReadSideProcess]): ZIO[Has[TReadSideProcessing], Throwable, KillSwitch] =
    ZIO.accessM[Has[TReadSideProcessing]](_.get.start(name, processes))

  val live: ZLayer[Has[ActorSystem] with Has[ReadSideSettings], Nothing, Has[TReadSideProcessing]] =
    ZLayer.fromServices[ActorSystem, ReadSideSettings, TReadSideProcessing] { (actorSystem: ActorSystem, readSideSettings: ReadSideSettings) =>
      ActorReadSideProcessing(actorSystem, readSideSettings)
    }
}
