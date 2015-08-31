name := "hpaste"

organization := "com.gravity"

organizationName := "Gravity"

organizationHomepage := Some(url("http://www.gravity.com"))

version := "0.1.29-CDH5.4.0"

licenses := Seq("Apache2" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt"))

homepage := Some(url("https://github.com/GravityLabs/HPaste"))

scalaVersion := "2.11.7"

crossScalaVersions := List("2.11.7", "2.10.5")

scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-language:postfixOps",
  "-language:existentials"
)

resolvers ++= Seq(
  "Cloudera" at "https://repository.cloudera.com/artifactory/cloudera-repos/"
)

libraryDependencies ++= Seq(
  "joda-time" % "joda-time" % "1.6.2", // "2.5",
  "org.apache.hadoop" % "hadoop-client" % "2.6.0-cdh5.4.0",
  "org.apache.hbase" % "hbase-common" % "1.0.0-cdh5.4.0" exclude("org.jruby", "jruby-complete"),
  "org.apache.hbase" % "hbase-client" % "1.0.0-cdh5.4.0" exclude("org.jruby", "jruby-complete"),
  "net.sf.trove4j" % "trove4j" % "3.0.3"
)

publishMavenStyle := true

pomIncludeRepository := { x => false }

publishTo <<= version { (v: String) =>
  val nexus = "http://nexus.rnd.unicredit.eu/"
  if (v.trim.endsWith("SNAPSHOT"))
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("3rdparty"  at nexus + "content/repositories/thirdparty")
}

credentials += Credentials(Path.userHome / ".ivy2" / ".credentials")
