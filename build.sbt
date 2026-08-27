name := "kv-store"
version := "0.1.0-SNAPSHOT"
scalaVersion := "2.12.18"

lazy val root = (project in file("."))
  .enablePlugins(PlayScala, ScoverageSbtPlugin)

coverageExcludedPackages := "<empty>;controllers.ref.*;controllers.javascript.*;controllers.Reverse*;controllers.routes.*;router.Routes*;router.javascript.*;router.ref.*;router.Reverse*"

libraryDependencies ++= Seq(
  guice,
  ws,
  "org.scalatestplus.play" %% "scalatestplus-play" % "5.1.0" % Test
)

// Raise the body-parser limit — the default 100 KB is too small for "arbitrary JSON"
PlayKeys.devSettings += "play.http.parser.maxMemoryBuffer" -> "10m"
