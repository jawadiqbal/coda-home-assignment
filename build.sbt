name := "kv-store"
version := "0.1.0-SNAPSHOT"
scalaVersion := "2.12.18"

lazy val root = (project in file("."))
  .enablePlugins(PlayScala, ScoverageSbtPlugin, SwaggerPlugin)

coverageExcludedPackages := "<empty>;controllers.ref.*;controllers.javascript.*;controllers.Reverse*;controllers.routes.*;router.Routes*;router.javascript.*;router.ref.*;router.Reverse*"

libraryDependencies ++= Seq(
  guice,
  ws,
  "org.webjars" % "swagger-ui" % "5.10.3",
  "org.scalatestplus.play" %% "scalatestplus-play" % "5.1.0" % Test
)

// Reflect models.* when a route $ref points at a case class. An empty
// namespace becomes "" and play-swagger then tries to reflect String.
swaggerDomainNameSpaces := Seq("models")
swaggerPrettyJson := true

// Scoverage reporter wants scala-xml 2.x; Play 2.8's twirl-api wants 1.2.0.
// "always" allows that mix without silencing every other eviction.
libraryDependencySchemes += "org.scala-lang.modules" %% "scala-xml" % "always"

// Raise the body-parser limit — the default 100 KB is too small for arbitrary JSON
PlayKeys.devSettings += "play.http.parser.maxMemoryBuffer" -> "10m"
