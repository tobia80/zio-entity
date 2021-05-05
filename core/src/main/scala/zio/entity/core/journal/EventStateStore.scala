package zio.entity.core.journal

import zio.Task
import zio.stream.Stream

trait EventStateStore[Key, Event, State] {
  type Offset = Long

  def clear(): Task[Unit]

  def appendEvent(key: Key, event: Event, offset: Offset): Task[Unit]

  def read(key: Key, offset: Offset): Stream[Throwable, Event]

  def readState: Task[(State, Offset)]

  def snapshotState(state: State): Task[Unit]

}
