package zio.entity.core

import zio.clock.Clock
import zio.{Has, UIO, ZIO, ZLayer}
import zio.duration.durationInt
import zio.entity.data.{EventTag, Tagging}
import zio.test.Assertion.{anything, equalTo, fails}
import zio.test.environment.TestEnvironment
import zio.test.{DefaultRunnableSpec, ZSpec}
import zio.test.assert

object KeyedAlgebraCombinatorsSpec extends DefaultRunnableSpec {

  private val algebraConfigLayer: ZLayer[Clock, Nothing, Has[AlgebraCombinatorConfig[String, Int, OpEvent]]] =
    MemoryStores.make[String, OpEvent, Int](100.millis, 2) to AlgebraCombinatorConfig.fromStores(Tagging.const(EventTag("test")))

  sealed trait OpEvent

  case class Add(number: Int) extends OpEvent
  case class Subtract(number: Int) extends OpEvent

  private val fold = Fold[Int, OpEvent](
    0,
    (state, event) =>
      event match {
        case Add(number)      => UIO.succeed(state + number)
        case Subtract(number) => UIO.succeed(state - number)
      }
  )

  override def spec: ZSpec[TestEnvironment, Any] = suite("A KeyedAlgebraCombinators")(
    testM("should read state")((for {
      config             <- ZIO.service[AlgebraCombinatorConfig[String, Int, OpEvent]]
      algebraCombinators <- KeyedAlgebraCombinators.fromParams("key", fold, identity, config)
      initialState       <- algebraCombinators.read
      _                  <- algebraCombinators.append(Add(3), Add(4))
      state              <- algebraCombinators.read
    } yield (assert(initialState)(equalTo(0)) && assert(state)(equalTo(7)))).provideSomeLayer[TestEnvironment](algebraConfigLayer)),
    testM("should reject") {
      (for {
        config                                                                    <- ZIO.service[AlgebraCombinatorConfig[String, Int, OpEvent]]
        algebraCombinators: KeyedAlgebraCombinators[String, Int, OpEvent, String] <- KeyedAlgebraCombinators.fromParams("key", fold, _ => "failed", config)
        result                                                                    <- algebraCombinators.reject("test").flip
      } yield (assert(result)(equalTo("test")))).provideLayer(algebraConfigLayer)
    }
  )
}
