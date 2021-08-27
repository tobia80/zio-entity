ThisBuild / organization := "io.github.thehonesttech"
ThisBuild / organizationName := "The Honest Tech"
ThisBuild / organizationHomepage := Some(url("https://github.com/thehonesttech"))

ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/thehonesttech/zio-entity"),
    "scm:git@github.com:thehonesttech/zio-entity.git"
  )
)
ThisBuild / developers := List(
  Developer(
    id    = "thehonesttech",
    name  = "Tobia Loschiavo",
    email = "tobia.loschiavo@gmail.com",
    url   = url("https://github.com/thehonesttech")
  )
)

ThisBuild / description := "Zio-Entity, a distributed, high performance, functional event sourcing library"
ThisBuild / licenses := List("Apache 2" -> new URL("http://www.apache.org/licenses/LICENSE-2.0.txt"))
ThisBuild / homepage := Some(url("https://github.com/zio-entity"))

// Remove all additional repository other than Maven Central from POM
ThisBuild / pomIncludeRepository := { _ => false }
ThisBuild / publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value) Some("snapshots" at nexus + "content/repositories/snapshots")
  else Some("releases" at nexus + "service/local/staging/deploy/maven2")
}

publishTo := sonatypePublishToBundle.value
ThisBuild / publishMavenStyle := true