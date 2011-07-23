// Set the project name to the string 'My Project'
name := "My Project"

// The := method used in Name and Version is one of two fundamental methods.
// The other method is <<=
// All other initialization methods are implemented in terms of these.
version := "1.0"                                                                             

scalaVersion := "2.8.1"

resolvers ++= Seq(
  "snapshots-repo" at "http://scala-tools.org/repo-snapshots", 
  "typesafe-repo" at "http://repo.typesafe.com/typesafe/releases", 
  "Local Maven Repository" at "file://$M2_REPO"
)

libraryDependencies ++= Seq(
  "junit" % "junit" % "4.8" % "test",
  "org.scalaz" %% "scalaz-core" % "6.0.1",
  "org.specs2" %% "specs2" % "1.5" % "test"
)

libraryDependencies <<= (libraryDependencies, sbtVersion) { (deps, version) => 
  deps :+ ("com.typesafe.sbteclipse" %% "sbteclipse" % "1.3-RC1" extra("sbtversion" -> version))
}


mainClass in (Compile, run) := Some("com.mindrocksstudio.testpromises.TestPromise")

testFrameworks += new TestFramework("org.specs2.runner.SpecsFramework")

testOptions := Seq(Tests.Filter(s =>
  Seq("Spec", "Suite", "Unit", "all").exists(s.endsWith(_)) &&
    ! s.endsWith("FeaturesSpec") ||
    s.contains("UserGuide") || 
    s.matches("org.specs2.guide.*")))

/** Console */
initialCommands in console := "import org.specs2._"


// Exclude backup files by default.  This uses ~=, which accepts a function of
//  type T => T (here T = FileFilter) that is applied to the existing value.
// A similar idea is overriding a member and applying a function to the super value:
//  override lazy val defaultExcludes = f(super.defaultExcludes)
//
defaultExcludes ~= (filter => filter || "*~")
//  Some equivalent ways of writing this:
//defaultExcludes ~= (_ || "*~")
//defaultExcludes ~= ( (_: FileFilter) || "*~")
//defaultExcludes ~= ( (filter: FileFilter) => filter || "*~")


// Use the project version to determine the repository to publish to.
//publishTo <<= version { (v: String) =>
//  if(v endsWith "-SNAPSHOT")
//    Some(ScalaToolsSnapshots)
//  else
//    Some(ScalaToolsReleases)
//}