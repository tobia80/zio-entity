package zio.entity.data

final case class EntityEvent[K, A](entityKey: K, sequenceNr: Long, payload: A)
