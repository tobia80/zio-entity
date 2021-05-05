package zio.entity.data

final case class EntityEvent[K, A](entityKey: K, sequenceNr: Long, payload: A) {
  def map[B](f: A => B): EntityEvent[K, B] = copy(payload = f(payload))
}
