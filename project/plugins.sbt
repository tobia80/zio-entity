addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.4")
addSbtPlugin("org.scalameta" % "sbt-mdoc" % "2.2.23" )
addSbtPlugin("com.github.sbt"    % "sbt-pgp"      % "2.1.2")
addSbtPlugin("org.xerial.sbt"    % "sbt-sonatype" % "3.9.9")
addSbtPlugin("com.github.gseitz" % "sbt-release"  % "1.0.13")

libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.11.5"