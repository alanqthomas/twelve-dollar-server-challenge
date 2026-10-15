// The $12 Server Challenge: Scala 3 + ZIO + zio-http on SQLite. `sbt assembly` -> target/server.jar
// SQLite itself is a C library build.sh compiles (target/libsqlite3.so), called through src/main/java/server/sqlite.
val nettyVersion = "4.2.19.Final"

lazy val root = (project in file("."))
  .settings(
    name         := "twelve-dollar-server",
    scalaVersion := "3.10.0",
    scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-Wunused:all"),
    libraryDependencies ++= Seq(
      "dev.zio"   %% "zio"         % "2.1.27",
      "dev.zio"   %% "zio-http"    % "3.11.6",
      "dev.zio"   %% "zio-json"    % "1.1.0",
      // zio-http declares Netty's io_uring transport optional; Main prefers it when the kernel supports it.
      ("io.netty"  % "netty-transport-native-io_uring" % nettyVersion).classifier("linux-x86_64"),
    ),
    // zio-http 3.11.6 was built against ZIO 2.1.26 and Netty 4.2.18; keep every module on the latest patch release.
    dependencyOverrides += "dev.zio" %% "zio-streams" % "2.1.27",
    dependencyOverrides ++= Seq(
      "netty-buffer", "netty-codec-base", "netty-codec-compression", "netty-codec-http", "netty-codec-http2",
      "netty-codec-socks", "netty-common", "netty-handler", "netty-handler-proxy", "netty-resolver",
      "netty-transport", "netty-transport-classes-epoll", "netty-transport-native-unix-common",
      "netty-transport-native-epoll", "netty-transport-classes-io_uring", "netty-transport-native-io_uring",
      "netty-transport-classes-kqueue", "netty-transport-native-kqueue", "netty-codec-marshalling",
      "netty-codec-protobuf",
    ).map("io.netty" % _ % nettyVersion),
    excludeDependencies ++= Seq(
      ExclusionRule("com.lihaoyi", "unroll-annotation_3"),  // scala-library 3.10 ships scala.annotation.unroll
      ExclusionRule("io.netty", "netty-pkitesting"),         // test certificates (and BouncyCastle) for zio-http's SSL
      ExclusionRule("com.aayushatharva.brotli4j"),           // no compression here
    ),
    Compile / run / fork := true,
    assembly / mainClass             := Some("server.Main"),
    assembly / assemblyJarName       := "server.jar",
    assembly / assemblyOutputPath    := baseDirectory.value / "target" / "server.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "io.netty.versions.properties")      => MergeStrategy.first
      case PathList("META-INF", "versions", _, "module-info.class") => MergeStrategy.discard
      case "module-info.class"                                        => MergeStrategy.discard
      case PathList("META-INF", "native-image", _*)                   => MergeStrategy.discard
      case x                                                          => (assembly / assemblyMergeStrategy).value(x)
    },
  )
