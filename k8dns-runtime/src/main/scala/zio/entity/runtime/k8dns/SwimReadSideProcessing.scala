package zio.entity.runtime.k8dns

import zio.clock.Clock
import zio.duration.durationInt
import zio.entity.readside.{KillSwitch, ReadSideProcess, ReadSideProcessing}
import zio.{memberlist, Ref, Task, UIO, ZIO, ZLayer}
import zio.memberlist.{Memberlist, Swim}
import zio.stream.ZStream

class SwimReadSideProcessing(swim: Memberlist.Service[Byte], clock: Clock.Service) extends ReadSideProcessing {
  override def start(name: String, processes: List[ReadSideProcess]): ZStream[Any, Throwable, KillSwitch] = {
    def checkNodesAndRun: ZIO[Swim[Byte], Throwable, Task[Unit]] = for {
      nodes       <- memberlist.nodes[Byte]
      localMember <- memberlist.localMember[Byte]
      nodesToUse = if (nodes.isEmpty) Set(localMember) else nodes
      shardNode = ShardLogic.getShardNode(name, nodesToUse.toList)
      _                <- if (localMember != shardNode) ZIO.never else ZIO.unit
      runningProcesses <- ZIO.collectAll(processes.map(_.run))
      kill = ZIO.foreach(runningProcesses)(_.shutdown).unit
    } yield kill
    // every x seconds and checking events continue to check active nodes and if I am the node i, start the process
    ZStream
      .fromEffect(for {
        state <- Ref.make[Task[Unit]](Task.unit)
        kill  <- checkNodesAndRun
        _     <- state.set(kill)
        // if we have a static list, this is not needed, if we want to be fully dynamic, we have to start with events
        // we can start with static list and then go dynamic emit one signal and then evaluate the signal in order to know if I should return Zstream.never or the real stream
        _ <- memberlist
          .events[Byte]
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
      } yield KillSwitch(Task.fromFunctionM(_ => state.get.flatten)))
      .provideLayer(ZLayer.succeed(swim) ++ ZLayer.succeed(clock))
  }

}
