package zio.entity.core.journal

import zio.entity.data.EntityEvent

case class JournalEntry[O, K, E](offset: O, event: EntityEvent[K, E])
