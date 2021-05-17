package zio.entity.postgres.journal

import doobie.Update
import doobie.implicits.{toSqlInterpolator, _}
import doobie.util.transactor.Transactor
import zio.clock.Clock
import zio.duration.Duration
import zio.entity.core.journal.{EventJournal, JournalEntry, JournalQuery}
import zio.entity.data.{EntityEvent, EventTag}
import zio.entity.serializer.{SchemaCodec, SchemaEncoder}
import zio.interop.catz._
import zio.stream.ZStream
import zio.stream.interop.fs2z._
import zio.{Chunk, NonEmptyChunk, RIO, Ref, Schedule, Task, ZIO, ZLayer}
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import zio._
import zio.entity.core.snapshot.KeyValueStore
import zio.entity.serializer.{SchemaCodec, SchemaDecoder, SchemaEncoder}
import zio.interop.catz._

class PostgresEventJournal[Key: SchemaCodec, Event: SchemaCodec](
  pollingInterval: Duration,
  clock: Clock.Service,
  tableName: String,
  transactor: Transactor[Task]
) extends EventJournal[Key, Event]
    with JournalQuery[Long, Key, Event] {
  // table per entity

  case class Record(key: Array[Byte], offset: Long, event: Array[Byte], tags: List[String])
  override def append(key: Key, offset: Long, events: NonEmptyChunk[Event]): RIO[HasTagging, Unit] = {
    // bulk insert of elements
    ZIO.accessM[HasTagging] { tagging =>
      val tags = tagging.tag(key).map(_.value).toList
      val transformedEvents: List[Record] = events.zipWithIndex.map { case (el, index) =>
        Record(SchemaEncoder[Key].encode(key).toArray, offset + index, SchemaCodec[Event].encode(el).toArray, tags)
      }.toList
      Update[Record](s"""INSERT INTO $tableName (key, offset, event, tags) VALUES (?, ?, ?, ?)""")
        .updateMany(transformedEvents)
        .transact(transactor)
        .unit
    }
  }

  override def read(key: Key, offset: Long): zio.stream.Stream[Throwable, EntityEvent[Key, Event]] = {
    // query with sequence number bigger than offset
    val keyBytes: Array[Byte] = SchemaEncoder[Key].encode(key).toArray
    val valueDecoder = SchemaCodec[Event]

    sql"""SELECT * FROM $tableName where key = $keyBytes and offset >= $offset""".query[Record].stream.transact(transactor).toZStream().mapM {
      case Record(_, recordOffset, event, _) =>
        val eventZio: Task[Event] = ZIO.fromTry(valueDecoder.decode(Chunk.fromArray(event)))
        eventZio.map(event => EntityEvent(key, recordOffset, event))
    }
  }

  override def eventsByTag(tag: EventTag, offset: Option[Long]): ZStream[Any, Throwable, JournalEntry[Long, Key, Event]] = (for {
    lastOffsetProcessed <- ZStream.fromEffect(Ref.make[Option[Long]](None))
    _                   <- ZStream.fromSchedule(Schedule.fixed(pollingInterval))
    lastOffset <- ZStream
      .fromEffect(lastOffsetProcessed.get)
    journalEntry <- currentEventsByTag(tag, lastOffset.orElse(offset)).mapM { event =>
      lastOffsetProcessed.set(Some(event.offset)).as(event)
    }
  } yield journalEntry).provideLayer(ZLayer.succeed(clock))

  override def currentEventsByTag(tag: EventTag, offset: Option[Long]): zio.stream.Stream[Throwable, JournalEntry[Long, Key, Event]] = {
    val offsetValue = offset.getOrElse(0L)
    val tagValue = tag.value
    val valueDecoder = SchemaCodec[Event]
    val keyDecoder = SchemaCodec[Key]

    sql"""SELECT * FROM $tableName  where offset >= $offsetValue and tags contains ${tagValue}""".query[Record].stream.transact(transactor).toZStream().mapM {
      case Record(keyBytes, recordOffset, eventBytes, tags) =>
        for {
          key   <- ZIO.fromTry(keyDecoder.decode(Chunk.fromArray(keyBytes)))
          event <- ZIO.fromTry(valueDecoder.decode(Chunk.fromArray(eventBytes)))
        } yield JournalEntry(recordOffset, EntityEvent(key, recordOffset, event))
    }
  }
}
