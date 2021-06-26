package zio.entity.runtime.k8dns.protocol

import zio.{Promise, Task}
import zio.entity.runtime.k8dns.Message
import zio.memberlist.NodeAddress
import zio.stream.ZStream

trait NodeMessagingProtocol {

  def ask(nodeAddress: NodeAddress, payload: Message): Task[Message]

  val receive: ZStream[Any, Throwable, (NodeAddress, Message, Message => Task[Unit])]

  def updateConnections(nodes: Set[NodeAddress]): Task[Unit]
}
