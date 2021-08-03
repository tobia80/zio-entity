package zio.entity.macros

import scalapb.UnknownFieldSet
import zio.Chunk
import zio.schema.Schema

import java.util.UUID

object Schemas {
  implicit val uuid: Schema[UUID] = Schema.primitive[String].transform(UUID.fromString, _.toString)
  implicit val map: Schema[Map[Int, UnknownFieldSet.Field]] = Schema.primitive[String].transform(_ => Map.empty, _ => "")
  implicit def mapSchema[A, B](implicit schemaA: Schema[A], schemaB: Schema[B]): Schema[Map[A, B]] = Schema.chunk[(A, B)].transform(_.toMap, Chunk.fromIterable)
}
