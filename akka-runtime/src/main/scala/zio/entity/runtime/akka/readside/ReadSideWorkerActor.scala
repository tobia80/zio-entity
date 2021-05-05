package zio.entity.runtime.akka.readside

import akka.actor.{Actor, ActorLogging, Props, Status}
import akka.pattern._
import zio.entity.readside.{ReadSideProcess, RunningProcess}
import zio.entity.runtime.akka.readside.ReadSideWorkerActor.KeepRunning
import zio.entity.runtime.akka.readside.serialization.ReadSideMessage
import zio.{Runtime, Task}

object ReadSideWorkerActor {
  def props(processWithId: Int => ReadSideProcess, processName: String)(implicit runtime: Runtime[Any]): Props =
    Props(new ReadSideWorkerActor(processWithId, processName))

  final case class KeepRunning(workerId: Int) extends ReadSideMessage

}

final class ReadSideWorkerActor(
  processFor: Int => ReadSideProcess,
  processName: String
)(implicit val runtime: Runtime[Any])
    extends Actor
    with ActorLogging {

  import context.dispatcher

  case class ProcessStarted(process: RunningProcess)

  case object ProcessTerminated

  var killSwitch: Option[Task[Unit]] = None

  override def postStop(): Unit =
    killSwitch.foreach(el => runtime.unsafeRun(el))

  def receive: Receive = { case KeepRunning(workerId) =>
    log.info("[{}] Starting process {}", workerId, processName)
    runtime.unsafeRunToFuture(
      processFor(workerId).run
        .map(ProcessStarted)
    ) pipeTo self
    context.become {
      case ProcessStarted(RunningProcess(watchTermination, terminate)) =>
        log.info("[{}] Process started {}", workerId, processName)
        killSwitch = Some(terminate)
        runtime.unsafeRunToFuture(watchTermination.as(ProcessTerminated)) pipeTo self
        context.become {
          case Status.Failure(e) =>
            log.error(e, "Process failed {}", processName)
            throw e
          case ProcessTerminated =>
            log.error("Process terminated {}", processName)
            throw new IllegalStateException(s"Process terminated $processName")
        }
      case Status.Failure(e) =>
        log.error(e, "Process failed to start {}", processName)
        throw e
      case KeepRunning(_) => ()
    }
  }
}
