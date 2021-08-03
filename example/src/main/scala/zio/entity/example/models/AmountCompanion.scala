package zio.entity.example.models

import zio.entity.example.Amount.Currency
import zio.schema.Schema

trait AmountCompanion {

  implicit val currency: Schema[Currency] = Schema.primitive[Int].transform(n => Currency.fromValue(n), _.value)

}
