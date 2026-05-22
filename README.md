# jfrtofp
![Sonatype Nexus (Snapshots)](https://img.shields.io/nexus/s/me.bechberger/jfrtofp?server=https%3A%2F%2Fs01.oss.sonatype.org)

JFR to [Firefox Profiler](https://profiler.firefox.com) converter for JDK 11+.

*This is in alpha state, it does not work with really large JFR files and might still have bugs.*

It works best with this custom [Firefox Profiler fork](https://github.com/parttimenerd/firefox-profiler/tree/jfrtofp)
which includes many of our own PRs which are not yet upstream (and might be less stable).

## Web-based alternative (no JVM required)

If you don't need the CLI/server workflow, the [hosted Firefox Profiler fork](https://parttimenerd.github.io/firefox-profiler/)
includes an in-browser JFR converter — drag and drop a `.jfr` file into the page and
view it directly. The conversion runs locally in WebAssembly (built on
[Jaroslav Bachorik](https://github.com/jbachorik)'s [jafar](https://github.com/btraceio/jafar)
parser, compiled via [GraalVM Web Image](https://www.graalvm.org/latest/reference-manual/web-image/));
no file leaves your machine and no Java/JVM is needed locally.

For batch conversion, integration into another tool, or large recordings where
the in-browser converter is too slow, keep using `jfrtofp` / `jfrtofp-server` below.

## Basic Usage

We recommend using the [jfrtofp-server](https://github.com/parttimenerd/jfrtofp-server) which includes a
custom Firefox Profiler distribution with the converter and a webserver which serves both.

If you really want to use it directly, download the latest `jfrtofp-all.jar` release 
and simply pass the JFR file as its first argument:

```sh
  java -jar jfrtofp-all.jar samples/small_profile.jfr
```

This will produce a `samples/small_profile.json.gz` file, you can customize the output file
by passing the `--output <file>` option.

There is the possibility to produce [Speedscope](https://www.speedscope.app/) files as well, by passing the "--mode speedscope" option:

```sh
  java -jar jfrtofp-all.jar samples/small_profile.jfr --mode speedscope
```

This will produce a `samples/small_profile.json.gz` file. 
But this is considered experimental and not the main focus of this project.

## Run from Source

```sh
  git clone https://github.com/parttimenerd/jfrtofp.git
  cd jfrtofp
  ./gradlew run --args="samples/small_profile.jfr"
```

## Usage as a Library
```groovy
dependencies {
    implementation 'com.github.parttimenerd:jfrtofp:0.0.5-SNAPSHOT'
}

repositories {
    maven {
        url = "https://central.sonatype.com/repository/maven-snapshots/"
    }
}
```

## License
MIT, Copyright 2023 SAP SE or an SAP affiliate company, Johannes Bechberger
and jfrtofp contributors


*This project is a prototype of the [SapMachine](https://sapmachine.io) team
at [SAP SE](https://sap.com)*
