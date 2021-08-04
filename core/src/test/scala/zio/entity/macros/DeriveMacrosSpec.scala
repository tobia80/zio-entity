package zio.entity.macros

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import zio.entity.data.EntityProtocol
import zio.entity.macros.BoopickleCodec.codec
import zio.{Chunk, IO}

class DeriveMacrosSpec extends AnyFreeSpec {

  "Client macro" - {
    "called proto macro" in {
      val _: EntityProtocol[AlgebraImpl, String] = RpcMacro.derive[AlgebraImpl, String]
    }
  }

  "Codec" - {
    import boopickle.Default._
    import BoopickleCodec.chunkPickler

    val inputCodec = codec[(Int, String)]
    val mainCodec = codec[(String, Chunk[Byte])]

    "encode and decode" in {
      val input = (10, "hello")
      val inputEnc = zio.Runtime.default.unsafeRun(inputCodec.encode(input))
      println(inputEnc)
      val resultDec = zio.Runtime.default.unsafeRun(inputCodec.decode(inputEnc))
      resultDec should ===(input)
      val encodedBitVector = ("1", inputEnc)

      val result = zio.Runtime.default.unsafeRun(mainCodec.encode(encodedBitVector))

      val decmain = zio.Runtime.default.unsafeRun(mainCodec.decode(result))
      decmain should ===(encodedBitVector)
      val originalResult = zio.Runtime.default.unsafeRun(inputCodec.decode(decmain._2))
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
