package zio.entity.core.journal
import zio.entity.data.{EntityEvent, EventTag}
import zio.stream.ZStream
import zio.{stream, NonEmptyChunk, RIO, UIO}

class NoOpEventJournal[K, V] extends EventJournal[K, V] with JournalQuery[Long, K, V] {
  override def append(key: K, offset: Long, events: NonEmptyChunk[V]): RIO[HasTagging, Unit] = UIO.unit

  override def read(key: K, offset: Long): stream.Stream[Throwable, EntityEvent[K, V]] = ZStream.never

  override def eventsByTag(tag: EventTag, offset: Option[Long]): stream.Stream[Throwable, JournalEntry[Long, K, V]] = ZStream.never

  override def currentEventsByTag(tag: EventTag, offset: Option[Long]): stream.Stream[Throwable, JournalEntry[Long, K, V]] = ZStream.never
}
