package zio.entity.core

// TODO use invoke the macro here with inline
case class EventSourcedBehaviour[+Algebra, State, Event, Reject](
  algebra: Combinators[State, Event, Reject] => Algebra,
  eventHandler: Fold[State, Event],
  errorHandler: Throwable => Reject
)
