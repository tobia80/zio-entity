package zio.entity.runtime.k8dns

import zio.{Chunk, Task}
import zio.memberlist.NodeAddress

// it could use bidirectional streaming in grpc dealing with messages
trait NodeMessaging {

  def sendTo(nodeAddress: NodeAddress, payload: Chunk[Byte]): Task[Chunk[Byte]]

}
