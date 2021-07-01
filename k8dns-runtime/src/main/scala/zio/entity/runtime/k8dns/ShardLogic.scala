package zio.entity.runtime.k8dns

import zio.memberlist.NodeAddress

// TODO address the problem of rebalancing the shard!
object ShardLogic {

  def getShardNode(key: String, nodes: List[NodeAddress]): NodeAddress = {
    val numberOfShards = nodes.size
    val nodeIndex: Int = scala.math.abs(key.hashCode) % numberOfShards
    nodes(nodeIndex)
  }
}
