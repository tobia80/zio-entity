package zio.entity.core.journal

import zio.Task
import zio.duration.Duration
import zio.entity.serializer.{SchemaCodec, SchemaEncoder}

trait JournalStoreFactory {

  def buildJournalStore[K: SchemaEncoder, E: SchemaCodec](
    entityName: String,
    pollingInterval: Duration
  ): Task[EventJournal[K, E] with JournalQuery[Long, K, E]]

}
