package zio.entity.runtime.akka

import akka.actor.ActorSystem
import akka.cluster.sharding.ClusterShardingSettings

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{Duration, FiniteDuration}

final case class RuntimeSettings(
  numberOfShards: Int,
  idleTimeout: FiniteDuration,
  askTimeout: FiniteDuration,
  clusterShardingSettings: ClusterShardingSettings
)

object RuntimeSettings {

  /** Reads config from `zio.entity.akka-runtime`, see zio.entity.stem.conf for details
    *
    * @param system Actor system to get config from
    * @return default settings
    */
  def default(system: ActorSystem): RuntimeSettings = {
    val config = system.settings.config.getConfig("zio.entity.akka-runtime")

    def getMillisDuration(path: String): FiniteDuration =
      Duration(config.getDuration(path, TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS)

    RuntimeSettings(
      config.getInt("number-of-shards"),
      getMillisDuration("idle-timeout"),
      getMillisDuration("ask-timeout"),
      ClusterShardingSettings(system)
    )
  }
}
