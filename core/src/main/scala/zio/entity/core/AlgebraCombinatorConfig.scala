package zio.entity.core

import izumi.reflect.Tag
import zio.{Has, ZLayer}
import zio.entity.core.journal.EventJournal
import zio.entity.core.snapshot.{KeyValueStore, Snapshotting}
import zio.entity.data.Tagging

case class AlgebraCombinatorConfig[Key: Tag, State: Tag, Event: Tag](
  eventJournalOffsetStore: KeyValueStore[Key, Long],
  tagging: Tagging[Key],
  eventJournal: EventJournal[Key, Event],
  snapshotting: Snapshotting[Key, State]
)

object AlgebraCombinatorConfig {

  def live[Key: Tag, State: Tag, Event: Tag]: ZLayer[Has[KeyValueStore[Key, Long]] with Has[Tagging[Key]] with Has[EventJournal[Key, Event]] with Has[
    Snapshotting[Key, State]
  ], Nothing, Has[AlgebraCombinatorConfig[Key, State, Event]]] =
    ZLayer
      .fromServices[KeyValueStore[Key, Long], Tagging[Key], EventJournal[Key, Event], Snapshotting[Key, State], AlgebraCombinatorConfig[Key, State, Event]] {
        (
          offsetStore: KeyValueStore[Key, Long],
          tagging: Tagging[Key],
          eventJournal: EventJournal[Key, Event],
          snapshotting: Snapshotting[Key, State]
        ) =>
          AlgebraCombinatorConfig(offsetStore, tagging, eventJournal, snapshotting)
      }

}
