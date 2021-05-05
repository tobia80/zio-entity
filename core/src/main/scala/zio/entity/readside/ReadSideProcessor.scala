package zio.entity.readside

import zio.clock.Clock
import zio.duration.durationInt
import zio.entity.core.journal.{CommittableJournalQuery, JournalEntry}
import zio.entity.data.Committable
import zio.{Has, Queue, Runtime, Schedule, Tag, Task, UIO, ULayer, ZEnv, ZIO, ZLayer}
import zio.stream.ZStream

trait ReadSideProcessor[Reject] {
  def readSideStream: ZStream[Any, Reject, KillSwitch]
}

final case class KillSwitch(shutdown: Task[Unit]) extends AnyVal

final case class RunningProcess(watchTermination: Task[Unit], shutdown: UIO[Unit])

final case class ReadSideProcess(run: Task[RunningProcess]) extends AnyVal

object ReadSideProcessor {

  def readSideStream[Id: Tag, Event: Tag, Offset: Tag, Reject: Tag](
    readSideParams: ReadSideParams[Id, Event, Reject],
    errorHandler: Throwable => Reject
  ): ZStream[Clock with Has[ReadSideProcessing] with Has[CommittableJournalQuery[Offset, Id, Event]], Reject, KillSwitch] = {
    // simplify and then improve it
    for {
      readSideProcessing <- ZStream.service[ReadSideProcessing]
      journal            <- ZStream.service[CommittableJournalQuery[Offset, Id, Event]]
      sources: Seq[ZStream[Any, Reject, Committable[JournalEntry[Offset, Id, Event]]]] = readSideParams.tagging.tags.map { tag =>
        journal.eventsByTag(tag, readSideParams.consumerId).mapError(errorHandler)
      }
      (streams, processes) <- ZStream.fromEffect(buildStreamAndProcesses(sources))
      ks                   <- ZStream.fromEffect(readSideProcessing.start(readSideParams.name, processes.toList).mapError(errorHandler))
      _ <- streams
        .map { stream =>
          stream
            .mapMPar(readSideParams.parallelism) { element =>
              val journalEntry = element.value
              val commit = element.commit
              val key = journalEntry.event.entityKey
              val event = journalEntry.event.payload
              readSideParams.logic(key, event).retry(Schedule.fixed(1.second)) <* commit.mapError(errorHandler)
            }
        }
        .flattenPar(sources.size)
    } yield ks
  }

  private def buildStreamAndProcesses[Offset: Tag, Event: Tag, Id: Tag, Reject](
    sources: Seq[ZStream[Any, Reject, Committable[JournalEntry[Offset, Id, Event]]]]
  ) = {
    for {
      queue <- Queue.bounded[ZStream[Any, Reject, Committable[JournalEntry[Offset, Id, Event]]]](sources.size)
      processes = sources.map { s =>
        ReadSideProcess {
          for {
            stopped <- zio.Promise.make[Reject, Unit]
            fiber   <- (queue.offer(s.interruptWhen(stopped)) *> stopped.await).fork
          } yield RunningProcess(fiber.join.unit.mapError(cause => new RuntimeException("Failure " + cause)), stopped.succeed().unit)
        }
      }
    } yield (ZStream.fromQueue(queue), processes)
  }

  def readSideSubscription[Id: Tag, Event: Tag, Offset: Tag, Reject: Tag](
    readsideParams: ReadSideParams[Id, Event, Reject],
    errorHandler: Throwable => Reject
  )(implicit runtime: Runtime[ZEnv]): ZIO[Clock with Has[ReadSideProcessing] with Has[CommittableJournalQuery[Offset, Id, Event]], Reject, KillSwitch] =
    readSideStream[Id, Event, Offset, Reject](readsideParams, errorHandler).runLast.map(_.getOrElse(KillSwitch(Task.unit)))

}

trait ReadSideProcessing {
  def start(name: String, processes: List[ReadSideProcess]): Task[KillSwitch]
}

object ReadSideProcessing {
  def start(name: String, processes: List[ReadSideProcess]): ZIO[Has[ReadSideProcessing], Throwable, KillSwitch] =
    ZIO.accessM[Has[ReadSideProcessing]](_.get.start(name, processes))

  val memory: ULayer[Has[ReadSideProcessing]] = ZLayer.succeed { (name: String, processes: List[ReadSideProcess]) =>
    {
      for {
        tasksToShutdown <- ZIO.foreach(processes)(process => process.run)
      } yield KillSwitch(ZIO.foreach(tasksToShutdown)(_.shutdown).unit)
    }
  }
}
