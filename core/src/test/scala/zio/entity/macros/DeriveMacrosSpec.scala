package zio.entity.macros

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import zio.entity.data.EntityProtocol
import zio.{Chunk, IO}

class DeriveMacrosSpec extends AnyFreeSpec {

  "Client macro" - {
    "called proto macro" in {
      val _: EntityProtocol[AlgebraImpl, String] = RpcMacro.derive[AlgebraImpl, String]
    }
  }

  "Codec" - {
    import zio.schema.DeriveSchema
    import zio.schema.codec.ProtobufCodec._

    val inputCodec = DeriveSchema.gen[(Int, String)]
    val mainCodec = DeriveSchema.gen[(String, Chunk[Byte])]

    "encode and decode" in {
      val input = (10, "hello")
      val inputEnc = encode(inputCodec)(input)
      val resultDec = decode(inputCodec)(inputEnc).getOrElse(fail("Error"))
      resultDec should ===(input)
      val encodedBitVector = ("1", inputEnc)
      val result = encode(mainCodec)(encodedBitVector)

      val decmain = decode(mainCodec)(result).getOrElse(fail("Error"))
      decmain should ===(encodedBitVector)
      val originalResult = decode(inputCodec)(decmain._2).getOrElse(fail("Error"))
      originalResult should ===(input)

    }
  }

}

class AlgebraImpl {
  type SIO[Return] = IO[String, Return]

  def operation1(param: String): SIO[String] = IO.succeed(param)
}

object AlgebraImpl {
  val errorHandler: Throwable => String = _.getMessage
}
