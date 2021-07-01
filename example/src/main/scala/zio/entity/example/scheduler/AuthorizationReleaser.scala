package zio.entity.example.scheduler

import zio.IO

/** Poll a db table and release the lock invoking release lock on cards
  */
trait AuthorizationReleaser {
  def release: IO[AuthorizationReleaserError, Unit]
}

sealed trait AuthorizationReleaserError
