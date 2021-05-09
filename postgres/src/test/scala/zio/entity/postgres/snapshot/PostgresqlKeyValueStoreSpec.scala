package zio.entity.postgres.snapshot

import zio.test.environment.TestEnvironment
import zio.test.{DefaultRunnableSpec, ZSpec}

class PostgresqlKeyValueStoreSpec extends DefaultRunnableSpec {
  override def spec: ZSpec[TestEnvironment, Any] = suite("A postgres key value store")(
    testM("Can store and retrieve values from db")(???)
  )
}
