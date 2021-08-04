package zio.entity

import zio.schema.Schema

import java.util.UUID

package object example {

  import scalapb.TypeMapper

  private def toCommonBigDecimalValue(bigDecimal: scala.math.BigDecimal): BDecimal =
    BDecimal(bigDecimal.longValue, bigDecimal.scale)

  private def fromCommonBigDecimalValue(el: BDecimal): scala.math.BigDecimal = scala.math.BigDecimal(el.unscaledValue, el.scale)

  implicit val bigDecimalConversions =
    TypeMapper[BDecimal, scala.math.BigDecimal](in => fromCommonBigDecimalValue(in))(toCommonBigDecimalValue)

  implicit val uuidConversion = TypeMapper[PUUID, java.util.UUID](in => UUID.fromString(in.value))(out => PUUID(out.toString))
}
