
name := "sv_blg"

lazy val unionSettings = Seq(
  libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-http" % "10.1.5",
    "com.typesafe.akka" %% "akka-http-testkit" % "10.1.5" % Test,
    "com.typesafe.akka" %% "akka-http-spray-json" % "10.1.0",
    "com.typesafe.akka" %% "akka-actor" % "2.5.17",
    "com.typesafe.akka" %% "akka-testkit" % "2.5.17" % Test,
    "com.typesafe.akka" %% "akka-stream" % "2.5.17",
    "com.typesafe.akka" %% "akka-stream-testkit" % "2.5.17" % Test,
    "org.scalatest" %% "scalatest" % "3.0.5" % "test",
  ),

  assemblyMergeStrategy in assembly := {
    case PathList("META-INF", xs @ _*) => MergeStrategy.discard
    case PathList("reference.conf") => MergeStrategy.concat
    case x => MergeStrategy.first
  }
)

lazy val commonSettings = Seq(
  organization := "ru.serbis.svc",
  version := "0.1.2",
  scalaVersion := "2.12.7",

  libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-persistence" % "2.5.17",
    "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion,
    "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf",
    "com.typesafe.akka" %% "akka-persistence-cassandra" % "0.91",
    "com.typesafe.akka" %% "akka-persistence-cassandra-launcher" % "0.91" % Test,
  )
)

lazy val serviceSettings = Seq(
  organization := "ru.serbis.otko.sv_blg",
  version := "0.1.0",
  scalaVersion := "2.12.7",

  assemblyJarName in assembly := "sv_blg-0.1.0.jar",
  test in assembly := {},
  mainClass in assembly := Some("ru.serbis.okto.sv_blg.Main"),
  //PB.protoSources in Compile += Seq(file("service/src/main/protobuf"))
  PB.targets in Compile := Seq(scalapb.gen() -> (sourceManaged in Compile).value),

  libraryDependencies ++= Seq(
    "commons-daemon" % "commons-daemon" % "1.0.15",
  )
)


lazy val root = (project in file(".")).aggregate(service, common)

lazy val service = (project in file("service")).settings(serviceSettings, unionSettings) dependsOn common

lazy val common = (project in file("common")).settings(commonSettings, unionSettings)