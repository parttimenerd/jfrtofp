# jfrtofp
JFR to [Firefox Profiler](profiler.firefox.com) converter

## Ideas from Andreas
- [ ] use dates ("12:00:34.2") instead of just seconds ("34.2")
- [ ] skip methods with less than x % of time in the method tables and flamegraphs (e.g. focus on the main culprits in a method),
  map these to "other"

## Ideas from Oliver
- [ ] which JVM version, params
- [ ] how long does the GC take (wall clock time), mark long GC times with a different color
- [ ] maybe import GC History / GC History viewer

## Other Ideas
- [ ] use the whole descriptor in the method name and use the resource only for actual files (?)
- [ ] package converter as a separate JAR and repository, then package the server with the converter
- [ ] use relative times instead of absolute times
- [ ] fork the FirefoxProfiler (friendly)
- [ ] combine several markers into one (collect all Environment variables)