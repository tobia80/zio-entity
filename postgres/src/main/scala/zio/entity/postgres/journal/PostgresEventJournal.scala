package zio.entity.postgres.journal

import zio.blocking.Blocking
import zio.clock.Clock
import zio.duration.Duration
import zio.entity.core.journal.{EventJournal, JournalEntry, JournalQuery}
import zio.entity.data.{EntityEvent, EventTag}
import zio.entity.postgres.snapshot.MyPostgresContext
import zio.entity.serializer.{SchemaCodec, SchemaEncoder}
import zio.stream.ZStream
import zio.{Chunk, NonEmptyChunk, RIO, Ref, Schedule, Task, ZIO, ZLayer}

import java.sql.Connection

class PostgresEventJournal[Key: SchemaCodec, Event: SchemaCodec](
  pollingInterval: Duration,
  connection: Connection,
  blocking: Blocking.Service,
  clock: Clock.Service
) extends EventJournal[Key, Event]
    with JournalQuery[Long, Key, Event] {
  // table per entity
  import MyPostgresContext._

  type Record = (Chunk[Byte], Long, Chunk[Byte], List[String])
  private val layer = ZLayer.succeed(connection) and ZLayer.succeed(blocking)

  override def append(key: Key, offset: Long, events: NonEmptyChunk[Event]): RIO[HasTagging, Unit] = {
    // bulk insert of elements
    ZIO.accessM[HasTagging] { tagging =>
      val tags = tagging.tag(key).map(_.value).toList
      val transformedEvents: Chunk[Record] = events.zipWithIndex.map { case (el, index) =>
        (SchemaEncoder[Key].encode(key), offset + index, SchemaCodec[Event].encode(el), tags)
      }
      run(quote {
        liftQuery(transformedEvents).foreach(e => query[Record].insert(e))
      }).unit.provideLayer(layer)
    }
  }

  override def read(key: Key, offset: Long): zio.stream.Stream[Throwable, EntityEvent[Key, Event]] = {
    // query with sequence number bigger than offset
    //TODO change error type to Throwable
    val keyBytes: Chunk[Byte] = SchemaEncoder[Key].encode(key)
    val valueDecoder = SchemaCodec[Event]
    stream(quote {
      query[Record].filter { case (key, recordOffset, _, _) =>
        key == lift(keyBytes) && recordOffset >= lift(offset)
      }
    }).provideLayer(layer).mapM { case (_, recordOffset, event, _) =>
      val eventZio: Task[Event] = ZIO.fromTry(valueDecoder.decode(event))
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
    stream(quote {
      query[Record].filter { case (key, recordOffset, _, tags) =>
        recordOffset >= lift(offsetValue) && tags.contains(lift(tagValue))
      }
    }).provideLayer(layer).mapM { case (keyBytes, recordOffset, eventBytes, tags) =>
      for {
        key   <- ZIO.fromTry(keyDecoder.decode(keyBytes))
        event <- ZIO.fromTry(valueDecoder.decode(eventBytes))
      } yield JournalEntry(recordOffset, EntityEvent(key, recordOffset, event))
    }
  }
}
