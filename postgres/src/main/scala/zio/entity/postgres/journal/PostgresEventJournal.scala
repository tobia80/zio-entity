package zio.entity.postgres.journal

import doobie.{Update, Update0}
import doobie.implicits.{toSqlInterpolator, _}
import zio.clock.Clock
import zio.duration.Duration
import zio.entity.core.journal.{EventJournal, JournalEntry, JournalQuery}
import zio.entity.data.{EntityEvent, EventTag}
import zio.interop.catz._
import zio.stream.ZStream
import zio.stream.interop.fs2z._
import zio.{Chunk, NonEmptyChunk, RIO, Ref, Schedule, Task, ZIO, ZLayer}
import doobie.postgres.implicits._
import doobie.util.fragment.Fragment
import doobie.util.transactor.Transactor
import zio._
import zio.entity.serializer.{SchemaCodec, SchemaEncoder}
import zio.interop.catz._

class PostgresEventJournal[Key: SchemaCodec, Event: SchemaCodec](
  pollingInterval: Duration,
  clock: Clock.Service,
  tableName: String,
  transactor: Transactor[Task]
) extends EventJournal[Key, Event]
    with JournalQuery[Long, Key, Event] {
  // table per entity

  case class Record(key: Array[Byte], seqNr: Long, event: Array[Byte], tags: List[String])
  override def append(key: Key, offset: Long, events: NonEmptyChunk[Event]): RIO[HasTagging, Unit] = {
    // bulk insert of elements
    ZIO.accessM[HasTagging] { tagging =>
      val tags = tagging.tag(key).map(_.value).toList
      val transformedEvents: List[Record] = events.zipWithIndex.map { case (el, index) =>
        Record(SchemaEncoder[Key].encode(key).toArray, offset + index, SchemaCodec[Event].encode(el).toArray, tags)
      }.toList
      val appendQuery = Update[(Array[Byte], Long, Array[Byte], List[String])](s"""INSERT INTO $tableName (key, seq_nr, event, tags) VALUES (?, ?, ?, ?)""")
      val input = transformedEvents.map { event =>
        (event.key, event.seqNr, event.event, event.tags)
      }
      appendQuery
        .updateMany(input)
        .transact(transactor)
        .unit
    }
  }

  override def read(key: Key, offset: Long): zio.stream.Stream[Throwable, EntityEvent[Key, Event]] = {
    // query with sequence number bigger than offset
    val keyBytes: Array[Byte] = SchemaEncoder[Key].encode(key).toArray
    val valueDecoder = SchemaCodec[Event]

    (fr"SELECT * FROM " ++ Fragment
      .const(tableName) ++ fr" where key = $keyBytes and seq_nr >= $offset ORDER BY seq_nr ASC")
      .query[(Long, Array[Byte], Long, Array[Byte], List[String])]
      .stream
      .transact(transactor)
      .toZStream()
      .mapM { case (_, _, recordOffset, event, _) =>
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

    (fr"SELECT * FROM " ++ Fragment
      .const(tableName) ++ fr" where seq_nr >= $offsetValue and array_position(tags, ${tagValue} :: text) IS NOT NULL ORDER BY id ASC")
      .query[(Long, Array[Byte], Long, Array[Byte], List[String])]
      .stream
      .transact(transactor)
      .toZStream()
      .mapM { case (_, keyBytes, recordOffset, eventBytes, tags) =>
        for {
          key   <- ZIO.fromTry(keyDecoder.decode(Chunk.fromArray(keyBytes)))
          event <- ZIO.fromTry(valueDecoder.decode(Chunk.fromArray(eventBytes)))
        } yield JournalEntry(recordOffset, EntityEvent(key, recordOffset, event))
      }
  }
}

object PostgresqlEventJournal {

  type EventJournalStore[K, E] = EventJournal[K, E] with JournalQuery[Long, K, E]

  private def createTable(tableName: String, transactor: Transactor[Task]): Task[Unit] = {
    (for {
      _ <- Update0(
        s"""
        CREATE TABLE IF NOT EXISTS $tableName
          ( id BIGSERIAL,
           key BYTEA NOT NULL,
           seq_nr INTEGER NOT NULL,
           event BYTEA NOT NULL,
           tags TEXT[] NOT NULL
          )
        """,
        None
      ).run

      _ <- Update0(
        s"CREATE UNIQUE INDEX IF NOT EXISTS ${tableName}_id_uindex ON $tableName (id)",
        None
      ).run

      _ <- Update0(
        s"CREATE UNIQUE INDEX IF NOT EXISTS ${tableName}_key_seq_nr_uindex ON $tableName (key, seq_nr)",
        None
      ).run

      _ <- Update0(s"CREATE INDEX IF NOT EXISTS ${tableName}_tags ON $tableName (tags)", None).run
    } yield ()).transact(transactor).unit
  }

  def live[Key: SchemaCodec: Tag, Event: SchemaCodec: Tag](
    tableName: String,
    pollingInterval: Duration
  ): ZLayer[Has[Transactor[Task]] with Clock, Throwable, Has[EventJournalStore[Key, Event]]] = {
    for {
      xa    <- ZIO.service[Transactor[Task]]
      clock <- ZIO.service[Clock.Service]
      _     <- createTable(tableName, xa)
    } yield new PostgresEventJournal[Key, Event](pollingInterval, clock, tableName, xa)
  }.toLayer
}
