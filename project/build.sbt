// The scala-xml version spread between scala-compiler, twirl-api, and
// sbt-native-packager is harmless at runtime but trips sbt's eviction check.
// This must live in project/build.sbt (meta-build level) — putting it in
// the top-level build.sbt is too late, because this error fires before
// build.sbt is loaded.
ThisBuild / libraryDependencySchemes += "org.scala-lang.modules" %% "scala-xml" % "always"
