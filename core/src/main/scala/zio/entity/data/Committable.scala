package zio.entity.data

import zio.Task

case class Committable[+A](commit: Task[Unit], value: A) {
  def map[B](f: A => B): Committable[B] = copy(value = f(value))
  def traverse[B](f: A => Task[B]): Task[Committable[B]] =
    f(value).map(b => copy(value = b))
  def process[B](f: A => Task[B]): Task[B] = f(value) <* commit

}
