package zio.entity.data

import zio.Task

case class Committable[+A](commit: Task[Unit], value: A) {
  def map[B](f: A => B): Committable[B] = copy(value = f(value))
  def process[B](f: A => Task[B]): Task[B] = f(value) <* commit
}
