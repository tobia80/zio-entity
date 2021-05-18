import Dependencies._

lazy val root = (project in file("."))
  .settings(
    inThisBuild(
      List(
        organization := "zio",
        scalaVersion := "2.13.5",
        version := "0.1.3-SNAPSHOT"
      )
    ),
    name := "zio-entity"
  )
  .settings(noPublishSettings)

lazy val noPublishSettings = Seq(publish := (()), publishLocal := (()), publishArtifact := false)

val testDeps = Seq(
  "org.scalatest" %% "scalatest" % "3.2.8" % Test,
  "dev.zio" %% "zio-test" % zio % Test,
  "dev.zio" %% "zio-test-sbt" % zio % Test,
  "dev.zio" %% "zio-test-magnolia" % zio % Test
)

val allDeps = Seq(
  "dev.zio" %% "zio" % zio,
  "dev.zio" %% "zio-streams" % zio,
  "org.scodec" %% "scodec-bits" % "1.1.26",
  "org.scodec" %% "scodec-core" % "1.11.7",
  "io.suzaku" %% "boopickle" % "1.3.3",
  "io.github.kitlangton" %% "zio-magic" % "0.3.2",
  "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion
) ++ testDeps

val postgresDeps = Seq(
  "org.tpolecat" %% "doobie-core" % "0.13.2",
  "org.tpolecat" %% "doobie-hikari" % "0.13.2",
  "org.tpolecat" %% "doobie-postgres" % "0.13.2",
  "dev.zio" %% "zio-interop-cats" % "2.4.1.0",
  "org.testcontainers" % "postgresql" % "1.15.3" % Test
) ++ testDeps

val akkaDeps = Seq(
  "com.typesafe.akka" %% "akka-cluster-sharding" % "2.6.14",
  "com.typesafe.akka" %% "akka-cluster" % "2.6.14",
) ++ testDeps

lazy val commonProtobufSettings = Seq(
  Compile / PB.targets := Seq(
    scalapb.gen() -> (Compile / sourceManaged).value / "scalapb"
  ),
  Compile / PB.protoSources := Seq(
    baseDirectory.value / "src/schemas/protobuf"
  )
)

def module(id: String, path: String, description: String): Project =
  Project(id, file(path))
    .settings(moduleName := id, name := description)
    .settings(testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"))

lazy val `core` = module("zio-entity-core", "core", "Core library")
  .settings(libraryDependencies ++= allDeps)

lazy val `postgres` = module("zio-entity-postgres", "postgres", "Postgres event sourcing stores")
  .dependsOn(`core`)
  .settings(libraryDependencies ++= postgresDeps)
  .settings(commonProtobufSettings)

lazy val `akka-runtime` = module("zio-entity-akkaruntime", "akka-runtime", "Akka runtime")
  .dependsOn(`core`)
  .settings(libraryDependencies ++= akkaDeps)
  .settings(commonProtobufSettings)

lazy val docs = project       // new documentation project
  .in(file("zio-entity-docs")) // important: it must not be docs/
  .dependsOn(`core`, `akka-runtime`, `postgres`)
  .enablePlugins(MdocPlugin)

aggregateProjects(`core`, `akka-runtime`, `postgres`)

ThisBuild / parallelExecution := false
testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")