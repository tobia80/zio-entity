package zio.entity.runtime.k8dns

import zio.clock.Clock
import zio.duration.durationInt
import zio.entity.readside.{KillSwitch, ReadSideProcess, ReadSideProcessing}
import zio.{memberlist, IO, Ref, Task, UIO, ZIO, ZLayer}
import zio.memberlist.{Memberlist, Swim}
import zio.stream.ZStream

class SwimReadSideProcessing(swim: Memberlist.Service[Byte], clock: Clock.Service) extends ReadSideProcessing {
  override def start(name: String, processes: List[ReadSideProcess]): ZStream[Any, Throwable, KillSwitch] = {
    //  call members, check if I have to execute the event, if previously was executing and now not anymore, kill it  set the state
    // every x seconds and checking events continue to check active nodes and if I am the node i, start the process
    (for {
      state        <- ZStream.fromEffect(Ref.make[Task[Unit]](Task.unit))
      amIExecuting <- ZStream.fromEffect(Ref.make[Boolean](false))
      _            <- ZStream.unit ++ memberlist.events[Byte]
      nodes        <- ZStream.fromEffect(memberlist.nodes[Byte])
      localMember  <- ZStream.fromEffect(memberlist.localMember[Byte])
      nodesToUse = if (nodes.isEmpty) Set(localMember) else nodes
      shardNode = ShardLogic.getShardNode(name, nodesToUse.toList)
      executingPreviously <- ZStream.fromEffect(amIExecuting.get)
      shouldIExecuteNow = localMember == shardNode
      _: Unit <-
        ZStream.fromEffect(if (!(executingPreviously && shouldIExecuteNow)) {
          if (shouldIExecuteNow) {
            for {
              runningProcesses <- ZIO.collectAll(processes.map(_.run))
              kill = ZIO.foreach(runningProcesses)(_.shutdown).unit
              _ <- state.set(kill)
            } yield ()
          } else {
            for {
              killel <- state.get
              _      <- killel
              _      <- IO.never
            } yield ()
          }
        } else IO.unit)
    } yield KillSwitch(Task.fromFunctionM(_ => state.get.flatten))).provideLayer(ZLayer.succeed(swim) ++ ZLayer.succeed(clock))
  }

}
