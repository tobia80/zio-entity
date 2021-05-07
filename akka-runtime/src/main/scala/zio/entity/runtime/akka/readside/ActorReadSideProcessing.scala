package zio.entity.runtime.akka.readside

import akka.actor.ActorSystem
import akka.cluster.sharding.ClusterSharding
import akka.pattern.{ask, BackoffOpts, BackoffSupervisor}
import akka.util.Timeout
import zio.entity.readside.{KillSwitch, ReadSideProcess, ReadSideProcessing}
import zio.entity.runtime.akka.readside.ReadSideWorkerActor.KeepRunningWithWorker
import zio.{Task, ZIO}

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object ActorReadSideProcessing {
  def apply(system: ActorSystem, settings: ReadSideSettings): ReadSideProcessing = new ActorReadSideProcessing(system, settings)

}

final class ActorReadSideProcessing private (system: ActorSystem, settings: ReadSideSettings) extends ReadSideProcessing {

  /** Starts `processes` distributed over underlying akka cluster.
    *
    * @param name      - type name of underlying cluster sharding
    * @param processes - list of processes to distribute
    */
  def start(name: String, processes: List[ReadSideProcess]): Task[KillSwitch] = {
    ZIO.runtime[Any].flatMap { runtime =>
      Task {
        val opts = BackoffOpts
          .onFailure(
            ReadSideWorkerActor.props(processes, name)(runtime),
            "worker",
            settings.minBackoff,
            settings.maxBackoff,
            settings.randomFactor
          )

        val props = BackoffSupervisor.props(opts)

        val region = ClusterSharding(system).start(
          typeName = name,
          entityProps = props,
          settings = settings.clusterShardingSettings,
          extractEntityId = { case c @ KeepRunningWithWorker(workerId) =>
            (workerId.toString, c)
          },
          extractShardId = {
            case KeepRunningWithWorker(workerId) => (workerId % settings.numberOfShards).toString
            case other                           => throw new IllegalArgumentException(s"Unexpected message [$other]")
          }
        )

        val regionSupervisor = system.actorOf(
          ReadSideSupervisor
            .props(processes.size, region, settings.heartbeatInterval),
          "DistributedProcessingSupervisor-" + URLEncoder
            .encode(name, StandardCharsets.UTF_8.name())
        )
        implicit val timeout: Timeout = Timeout(settings.shutdownTimeout)
        KillSwitch {
          Task.fromFuture(ec => regionSupervisor ? ReadSideSupervisor.GracefulShutdown).unit
        }
      }
    }
  }
}
