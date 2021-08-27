import java.time.Year

lazy val contributors = Seq(
  "thehonesttech" -> "Tobia Loschiavo"
)

pgpSecretRing := pgpPublicRing.value

publishTo := sonatypePublishTo.value

sonatypeProfileName := "io.github.thehonesttech"
publishMavenStyle := true
pomExtra := {
  <developers>
    {
    for ((username, name) <- contributors)
      yield <developer>
        <id>{username}</id>
        <name>{name}</name>
        <url>http://github.com/{username}</url>
      </developer>
    }
  </developers>
}
scmInfo := Some(
  ScmInfo(
    url("https://github.com/thehonesttech/zio-entity"),
    "scm:git@github.com:thehonesttech/zio-entity.git"
  )
)
licenses := Seq("MIT" -> url("http://opensource.org/licenses/MIT"))
homepage := Some(url("https://github.com/paoloboni/binance-scala-client"))