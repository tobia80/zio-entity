package zio.entity.runtime.k8dns

import io.grpc.internal.ThreadOptimizedDeframer
import zio.{Ref, Task}
import zio.memberlist.NodeAddress
import zio.stream.ZStream

// number of shards and one shard is independent from nodes. Assumption is that one shard is on one node only.
// so the rebalance of shard happens only on shards. We can use the queues separated by shard and by key communicate to others when shard rebalancing is terminated (broadast shardId -> terminated)

object ShardLogic {

  def getShardNode(key: String, nodes: List[NodeAddress]): NodeAddress = {
    val numberOfShards = nodes.size
    val nodeIndex: Int = scala.math.abs(key.hashCode) % numberOfShards
    nodes(nodeIndex)
  }
}

case class ShardId(value: String) extends AnyVal
case class Shards(mapping: Map[NodeAddress, List[ShardId]])
sealed trait ShardOperation
case class StartMoveToDifferentNode(shardId: ShardId, from: NodeAddress, to: NodeAddress) extends ShardOperation
case class EndMoveToDifferentNode(shardId: ShardId, from: NodeAddress, to: NodeAddress) extends ShardOperation
case class StartShardOnNode(shardId: ShardId, address: NodeAddress) extends ShardOperation

trait ShardCoordinator {

  def shards: Task[Shards]

  def shardRebalancingOperations: ZStream[Any, Throwable, ShardOperation]

  //send messages to the nodes, receives messages and decides how to manage shards (it uses Raft)
  def runShardCoordination: Task[Unit]

}

class ShardCoordinatorImpl(currentState: Ref[Shards]) extends ShardCoordinator {
  override def shards: Task[Shards] = ???

  override def shardRebalancingOperations: ZStream[Any, Throwable, ShardOperation] = {
    //get current state get new state, diff them and does it one at time
    // listen for new members

    ???
  }

  override def runShardCoordination: Task[Unit] = ???
}
