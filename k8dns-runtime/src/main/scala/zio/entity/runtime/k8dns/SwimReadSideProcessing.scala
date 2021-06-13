package zio.entity.runtime.k8dns

import zio.clock.Clock
import zio.duration.durationInt
import zio.entity.readside.{KillSwitch, ReadSideProcess, ReadSideProcessing}
import zio.{memberlist, Ref, Task, ZIO, ZLayer}
import zio.memberlist.{Memberlist, Swim}

class SwimReadSideProcessing(swim: Memberlist.Service[SwimMessage], clock: Clock.Service) extends ReadSideProcessing {
  override def start(name: String, processes: List[ReadSideProcess]): Task[KillSwitch] = {
    def checkNodesAndRun: ZIO[Swim[SwimMessage], Throwable, Task[Unit]] = for {
      nodes <- memberlist.nodes[SwimMessage]
      shardNode = ShardLogic.getShardNode(name, nodes.toList)
      localNode        <- memberlist.localMember[SwimMessage] if localNode == shardNode
      runningProcesses <- ZIO.collectAll(processes.map(_.run))
      kill = ZIO.foreach(runningProcesses)(_.shutdown).unit
    } yield kill
    // every x seconds and checking events continue to check active nodes and if I am the node i, start the process
    (for {
      state <- Ref.make[Task[Unit]](Task.unit)
      kill  <- checkNodesAndRun
      _     <- state.set(kill)
      _ <- memberlist
        .events[SwimMessage]
        .debounce(300.millis)
        .foreach { _ =>
          for {
            killel  <- state.get
            _       <- killel
            newKill <- checkNodesAndRun
            _       <- state.set(newKill)
          } yield ()
        }
        .fork
    } yield KillSwitch(Task.fromFunctionM(_ => state.get.flatten))).provideLayer(ZLayer.succeed(swim) ++ ZLayer.succeed(clock))
  }

}
