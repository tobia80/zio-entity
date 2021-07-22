addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.4")
addSbtPlugin("org.scalameta" % "sbt-mdoc" % "2.2.22" )

//libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.11.3"
val zioGrpcVersion = "0.5.0"


libraryDependencies += "com.thesamet.scalapb.zio-grpc" %% "zio-grpc-codegen" % zioGrpcVersion