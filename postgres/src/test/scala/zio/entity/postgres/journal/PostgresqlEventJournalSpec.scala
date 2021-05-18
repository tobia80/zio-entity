package zio.entity.postgres.journal

import zio.clock.Clock
import zio.duration.durationInt
import zio.entity.data.{EventTag, Tagging}
import zio.entity.postgres.example.{AValue, AnEvent, AnEventMessage, FirstEventHappened, Key}
import zio.entity.postgres.journal.PostgresqlEventJournal.EventJournalStore
import zio.entity.postgres.snapshot.PostgresqlTestContainerManaged
import zio.entity.serializer.protobuf.ProtobufCodecs._
import zio.test.Assertion.equalTo
import zio.test.environment.TestEnvironment
import zio.test.{assert, DefaultRunnableSpec, ZSpec}
import zio.{Has, NonEmptyChunk, ZIO, ZLayer}
import zio.entity.serializer.protobuf.ProtobufCodecs._

object PostgresqlEventJournalSpec extends DefaultRunnableSpec {

  private implicit val eventCodec = codedSealed[AnEvent, AnEventMessage]

  private val layer: ZLayer[Clock, Throwable, Has[EventJournalStore[Key, AnEvent]]] =
    (Clock.any and PostgresqlTestContainerManaged.transact) to PostgresqlEventJournal.live[Key, AnEvent]("testevent", 100.millis)

  private val tagging = Tagging.const[Key](EventTag("ok"))

  override def spec: ZSpec[TestEnvironment, Any] = suite("A postgres journal store")(
    testM("Can store and retrieve values from db") {
      (for {
        eventJournal <- ZIO.service[EventJournalStore[Key, AnEvent]]
        _            <- eventJournal.append(Key("1"), 0, NonEmptyChunk(FirstEventHappened(1, List("a", "b")), FirstEventHappened(2, Nil))).provide(tagging)
        events       <- eventJournal.read(Key("1"), 0).runCollect
      } yield (assert(events.size)(equalTo(2)))).provideCustomLayer(layer)
    }
  )
}
