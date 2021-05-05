package zio.entity.core

import izumi.reflect.Tag
import zio.entity.core.Combinators.ImpossibleTransitionException
import zio.entity.data.Versioned
import zio.{Chunk, IO, NonEmptyChunk, Ref, Task, UIO, ZIO}

class KeyedAlgebraCombinators[Key: Tag, State: Tag, Event: Tag, Reject](
  key: Key,
  state: Ref[Option[State]],
  userBehaviour: Fold[State, Event],
  errorHandler: Throwable => Reject,
  algebraCombinatorConfig: AlgebraCombinatorConfig[Key, State, Event]
) extends Combinators[State, Event, Reject] {
  import algebraCombinatorConfig._
  type Offset = Long

  override def read: IO[Reject, State] = {
    val result = state.get.flatMap {
      case Some(state) =>
        IO.succeed(state)
      case None =>
        // read from database (I need the key) and run the events until that state to now
        for {
          stateReturned <- recover
          _             <- state.set(Some(stateReturned))
        } yield stateReturned
    }
    result
  }

  override def append(es: Event, other: Event*): IO[Reject, Unit] = {
    //append event and store offset
    // read the offset by key
    for {
      offset <- getOffset
      events: NonEmptyChunk[Event] = NonEmptyChunk(es, other: _*)
      currentState <- read
      newState     <- userBehaviour.init(currentState).run(events).mapError(errorHandler)
      _            <- state.set(Some(newState))
      _            <- eventJournal.append(key, offset, events).mapError(errorHandler).provide(tagging)
      _            <- snapshotting.snapshot(key, Versioned(offset, currentState), Versioned(offset + events.size, newState)).mapError(errorHandler)
      _            <- eventJournalOffsetStore.setValue(key, offset + events.size).mapError(errorHandler)
    } yield ()
  }

  override def reject[A](r: Reject): IO[Reject, A] = IO.fail(r)

  private def getOffset: IO[Reject, Offset] = eventJournalOffsetStore.getValue(key).bimap(errorHandler, _.getOrElse(0L))

  private def recover: IO[Reject, State] = {
    snapshotting.load(key).mapError(errorHandler).flatMap { versionedStateMaybe =>
      // if nothing there, get initial state
      // I need current offset from offset store
      val (offset, readStateFromSnapshot) =
        versionedStateMaybe.fold(0L -> userBehaviour.initial)(versionedState => versionedState.version -> versionedState.value)

      // read until the current offset
      getOffset.flatMap {
        case offsetValue if offsetValue > 0 =>
          // read until offsetValue
          val foldBehaviour = userBehaviour.init(readStateFromSnapshot)
          eventJournal
            .read(key, offset)
            .foldWhileM(readStateFromSnapshot -> offset) { case (_, foldedOffset) => foldedOffset == offsetValue } { case ((state, _), entityEvent) =>
              foldBehaviour
                .reduce(state, entityEvent.payload)
                .bimap(
                  {
                    case Fold.ImpossibleException => ImpossibleTransitionException(state, entityEvent)
                    case other                    => other
                  },
                  { processedState =>
                    processedState -> entityEvent.sequenceNr
                  }
                )
            }
            .bimap(errorHandler, _._1)

        case _ => IO.succeed(readStateFromSnapshot)

      }

    }
  }

}

object KeyedAlgebraCombinators {
  def fromParams[Key: Tag, State: Tag, Event: Tag, Reject](
    key: Key,
    userBehaviour: Fold[State, Event],
    errorHandler: Throwable => Reject,
    algebraCombinatorConfig: AlgebraCombinatorConfig[Key, State, Event]
  ): UIO[KeyedAlgebraCombinators[Key, State, Event, Reject]] =
    Ref
      .make[Option[State]](None)
      .map { state =>
        new KeyedAlgebraCombinators[Key, State, Event, Reject](
          key,
          state,
          userBehaviour,
          errorHandler,
          algebraCombinatorConfig
        )
      }
}

// TODO: can the output be a IO instead?
final case class Fold[State, Event](initial: State, reduce: (State, Event) => Task[State]) {
  def init(a: State): Fold[State, Event] = copy(initial = a)

  def contramap[Y](f: Y => Event): Fold[State, Y] = Fold(initial, (a, c) => reduce(a, f(c)))

  def run(gb: Chunk[Event]): Task[State] =
    gb.foldM(initial)(reduce)

  def focus[B](get: State => B)(set: (State, B) => State): Fold[B, Event] =
    Fold(get(initial), (s, e) => reduce(set(initial, s), e).map(get))

  def expand[B](init: State => B, read: B => State, update: (B, State) => B): Fold[B, Event] =
    Fold(init(initial), (current, e) => reduce(read(current), e).map(update(current, _)))
}

object Fold {
  object ImpossibleException extends RuntimeException
  // TODO: add proper log
  val impossible: ZIO[Any, Throwable, Nothing] = Task.effectTotal(println("Impossible state exception")) *> Task.fail(ImpossibleException)
  def count[A]: Fold[Long, A] =
    Fold(0L, (c, _) => Task.succeed(c + 1L))
}
