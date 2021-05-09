package zio.entity.core.snapshot

import zio.Task
import zio.entity.data.Versioned
import zio.entity.serializer.{SchemaCodec, SchemaEncoder}

trait SnapshotStoreFactory {

  def buildSnapshotStore[K: SchemaEncoder, State: SchemaCodec](entityName: String): Task[KeyValueStore[K, Versioned[State]]]

}

trait OffsetStoreFactory {

  def buildOffsetStore[K: SchemaEncoder](entityName: String): Task[KeyValueStore[K, Long]]

}
