package zio.entity.postgres.journal

import zio.entity.core.journal.{EventJournal, JournalEntry, JournalQuery}
import zio.entity.data.{EntityEvent, EventTag}
import zio.stream.ZStream
import zio.{stream, NonEmptyChunk, RIO}

class PostgresEventJournal[Key, Event] extends EventJournal[Key, Event] with JournalQuery[Long, Key, Event] {
  override def append(key: Key, offset: Long, events: NonEmptyChunk[Event]): RIO[HasTagging, Unit] = ???

  override def read(key: Key, offset: Long): stream.Stream[Nothing, EntityEvent[Key, Event]] = ???

  override def eventsByTag(tag: EventTag, offset: Option[Long]): ZStream[Any, Throwable, JournalEntry[Long, Key, Event]] = ???

  override def currentEventsByTag(tag: EventTag, offset: Option[Long]): stream.Stream[Throwable, JournalEntry[Long, Key, Event]] = ???
}
