package zio.entity.core

case class EventSourcedBehaviour[Algebra, State, Event, Reject](
  algebra: Algebra,
  eventHandler: Fold[State, Event],
  errorHandler: Throwable => Reject
)
