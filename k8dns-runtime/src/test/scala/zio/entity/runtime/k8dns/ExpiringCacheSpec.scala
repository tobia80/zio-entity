package zio.entity.runtime.k8dns

import zio.duration.durationInt
import zio.test.environment.{TestClock, TestEnvironment}
import zio.test.{assertTrue, DefaultRunnableSpec, ZSpec}

object ExpiringCacheSpec extends DefaultRunnableSpec {
  private val live = ExpiringCache.live[String, Int](expireAfter = 20.seconds, checkEvery = 5.seconds)
  override def spec: ZSpec[TestEnvironment, Any] = suite("An expiring cache")(
    testM("can add and retrive elements if not expired")(
      (for {
        _    <- ExpiringCache.add("hello" -> 2)
        res  <- ExpiringCache.get[String, Int]("no")
        res2 <- ExpiringCache.get[String, Int]("hello")
      } yield (assertTrue(res.isEmpty, res2.contains(2)))).provideSomeLayer[TestEnvironment](live)
    ),
    testM("Elements expired cannot be retrieved anymore")(
      (for {
        _    <- ExpiringCache.add("hello" -> 2)
        _    <- TestClock.adjust(18.seconds)
        _    <- ExpiringCache.add("ok" -> 8)
        res  <- ExpiringCache.get[String, Int]("hello")
        _    <- TestClock.adjust(5.seconds)
        res2 <- ExpiringCache.get[String, Int]("hello")
        res3 <- ExpiringCache.get[String, Int]("ok")
      } yield (assertTrue(res.contains(2), res2.isEmpty, res3.contains(8)))).provideSomeLayer[TestEnvironment](live)
    )
  )
}
