package zio.entity.core

sealed trait Behaviour

// TODO use invoke the macro here with inline
case class EventSourcedBehaviour[+Algebra, State, Event, Reject](
  algebra: Combinators[State, Event, Reject] => Algebra,
  eventHandler: Fold[State, Event],
  errorHandler: Throwable => Reject
) extends Behaviour

case class AlgebraBehaviour[+Algebra](
  algebra: () => Algebra
) extends Behaviour
