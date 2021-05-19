package zio.entity.core.journal

import zio._
import zio.clock.Clock
import zio.duration.Duration
import zio.entity.core.snapshot.MemoryKeyValueStore
import zio.entity.data.{EntityEvent, EventTag, TagConsumer}
import zio.stream.ZStream

trait TestEventStore[Key, Event] {
  def getAppendedEvent(key: Key): Task[List[Event]]
  def getAppendedStream(key: Key): ZStream[Any, Nothing, Event]
}

class MemoryEventJournal[Key, Event](
  pollingInterval: Duration,
  internalStateEvents: Ref[Chunk[(Long, Key, Long, Event, List[String])]],
  internalQueue: Queue[(Key, Event)],
  clock: Clock.Service
) extends EventJournal[Key, Event]
    with JournalQuery[Long, Key, Event]
    with TestEventStore[Key, Event] {

  def getAppendedEvent(key: Key): Task[List[Event]] = internalStateEvents.get.map { list =>
    list.collect {
      case (idSequence, innerKey, sequenceNumber, event, tags) if innerKey == key => event
    }.toList
  }

  def getAppendedStream(key: Key): ZStream[Any, Nothing, Event] = ZStream.fromQueue(internalQueue).collect {
    case (internalKey, event) if internalKey == key => event
  }

  private val internal: ZRef[Nothing, Nothing, Chunk[(Long, Key, Long, Event, List[String])], Map[Key, Chunk[(Long, Long, Event, List[String])]]] = {
    internalStateEvents.map { elements =>
      elements
        .groupBy { element =>
          element._2
        }
        .view
        .mapValues { chunk =>
          chunk.map { case (idSequence, _, sequenceNumber, event, tags) =>
            (idSequence, sequenceNumber, event, tags)
          }
        }
        .toMap
    }
  }

  override def append(key: Key, sequenceNr: Long, events: NonEmptyChunk[Event]): RIO[HasTagging, Unit] =
    ZIO.accessM { tagging =>
      internalStateEvents.update { internalEvents =>
        val idSequenceToUse: Long = (internalEvents.lastOption.map(_._1).getOrElse(-1L)) + 1
        val tags = tagging.tag(key).map(_.value).toList
        internalEvents ++ events.zipWithIndex.map { case (event, index) =>
          (idSequenceToUse + index, key, index + sequenceNr, event, tags)
        }
      } *> internalQueue.offerAll(events.map(ev => key -> ev)).unit
    }

  override def read(key: Key, sequenceNr: Long): stream.Stream[Throwable, EntityEvent[Key, Event]] = {
    val a: UIO[List[EntityEvent[Key, Event]]] = internal
      .map(_.getOrElse(key, Chunk.empty).toList.drop(sequenceNr.toInt).map { case (_, index, event, _) =>
        EntityEvent(key, index, event)
      })
      .get
    stream.Stream.fromIterableM(a)
  }

  override def eventsByTag(tag: EventTag, offset: Option[Long]): ZStream[Any, Throwable, JournalEntry[Long, Key, Event]] = {
    (for {
      lastOffsetProcessed <- ZStream.fromEffect(Ref.make[Option[Long]](None))
      _                   <- ZStream.fromSchedule(Schedule.fixed(pollingInterval))
      lastOffset <- ZStream
        .fromEffect(lastOffsetProcessed.get)
      journalEntry <- currentEventsByTag(tag, lastOffset.orElse(offset)).mapM { event =>
        lastOffsetProcessed.set(Some(event.offset)).as(event)
      }
    } yield journalEntry).provideLayer(ZLayer.succeed(clock))
  }

  override def currentEventsByTag(tag: EventTag, offset: Option[Long]): stream.Stream[Throwable, JournalEntry[Long, Key, Event]] = {
    val a: ZIO[Any, Nothing, List[JournalEntry[Long, Key, Event]]] = internal.get.map { state =>
      state
        .flatMap { case (key, chunk) =>
          chunk.map { case (idSequence, sequenceNr, event, tags) =>
            (idSequence, key, sequenceNr, event, tags)
          }
        }
        .toList
        .sortBy(_._1)
        .drop(offset.map(_ + 1).getOrElse(0L).toInt)
        .collect {
          case (idSequence, key, sequenceNr, event, tagList) if tagList.contains(tag.value) =>
            JournalEntry(idSequence, EntityEvent(key, sequenceNr, event))
        }
    }
    stream.Stream.fromIterableM(a)
  }
}

object MemoryEventJournal {
  def make[Key, Event](pollingInterval: Duration): ZIO[Clock, Nothing, MemoryEventJournal[Key, Event]] = {
    for {
      internal <- Ref.make(Chunk[(Long, Key, Long, Event, List[String])]())
      queue    <- Queue.unbounded[(Key, Event)]
      clock    <- ZIO.service[Clock.Service]
    } yield new MemoryEventJournal[Key, Event](pollingInterval, internal, queue, clock)
  }
}

object MemoryCommitableEventJournal {
  def memoryCommittableJournalStore[K: Tag, E: Tag]: ZIO[Has[MemoryEventJournal[K, E]], Nothing, CommittableJournalQuery[Long, K, E]] = {
    ZIO.service[MemoryEventJournal[K, E]].flatMap { eventJournalStore =>
      MemoryKeyValueStore.make[TagConsumer, Long].map { readSideOffsetStore =>
        new CommittableJournalStore[Long, K, E](readSideOffsetStore, eventJournalStore)
      }
    }
  }
}
