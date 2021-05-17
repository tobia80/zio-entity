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

// TODO, important! I want to wait until system is ready before returning so I can use TestClock
final class ActorReadSideProcessing private (system: ActorSystem, settings: ReadSideSettings) extends ReadSideProcessing {

  /** Starts `processes` distributed over underlying akka cluster.
    *
    * @param name      - type name of underlying cluster sharding
    * @param processes - list of processes to distribute
    */
  def start(name: String, processes: List[ReadSideProcess]): Task[KillSwitch] = {
    ZIO.runtime[Any].flatMap { runtime =>
      ZIO.effect {
        val opts = BackoffOpts
          .onFailure(
            ReadSideWorkerActor.props(i => processes.apply(i), name)(runtime),
            "worker",
            settings.minBackoff,
            settings.maxBackoff,
            settings.randomFactor
          )

        val region = ClusterSharding(system).start(
          typeName = name,
          entityProps = BackoffSupervisor.props(opts),
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
          "ReadSideSupervisor-" + URLEncoder
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
