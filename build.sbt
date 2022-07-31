ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.8"

val calibanVersion = "2.0.1"

lazy val root = (project in file("."))
  .settings(
    name := "zio-http-caliban-example",
    libraryDependencies ++= Seq(
      "com.github.ghostdogpr" %% "caliban" % calibanVersion,
      "com.github.ghostdogpr" %% "caliban-zio-http" % calibanVersion,
      "io.d11" %% "zhttp" % "2.0.0-RC10",
      "dev.zio" %% "zio" % "2.0.0"
    )
  )
