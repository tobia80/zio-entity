package zio.entity.runtime.akka.readside

import akka.actor.ActorSystem
import akka.cluster.sharding.ClusterShardingSettings

import scala.concurrent.duration.{DurationInt, FiniteDuration}

final case class ReadSideSettings(
  minBackoff: FiniteDuration,
  maxBackoff: FiniteDuration,
  randomFactor: Double,
  shutdownTimeout: FiniteDuration,
  numberOfShards: Int,
  heartbeatInterval: FiniteDuration,
  clusterShardingSettings: ClusterShardingSettings
)

object ReadSideSettings {
  def default(clusterShardingSettings: ClusterShardingSettings): ReadSideSettings =
    ReadSideSettings(
      minBackoff = 3.seconds,
      maxBackoff = 10.seconds,
      randomFactor = 0.2,
      shutdownTimeout = 10.seconds,
      numberOfShards = 100,
      heartbeatInterval = 2.seconds,
      clusterShardingSettings = clusterShardingSettings
    )

  def default(system: ActorSystem): ReadSideSettings =
    default(ClusterShardingSettings(system))
}
