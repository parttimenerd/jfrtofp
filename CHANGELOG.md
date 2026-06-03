# jfrtofp

## Unreleased

- Fix handling of files without execution samples
  - (see https://github.com/parttimenerd/intellij-profiler-plugin/issues/30)
- Add `--execution-sample-type` to specify the type of execution samples to use
- Switch JFR parsing to [jafar](https://github.com/btraceio/jafar) for streaming
  reads
- Output-side memory now bounded via per-thread spill-to-disk + k-way merge
  (resolves https://github.com/parttimenerd/jfrtofp/issues/8 for the output path)
- Hot-path JSON now emitted through `BasicJSONGenerator` instead of
  `kotlinx.serialization`, removing per-marker `Map<String, JsonElement>` boxing
- Default-on output-size reductions: drop redundant marker `data["type"]`/
  `data["startTime"]`, drop `cause.time` ISO strings, quantize timestamps to 4
  decimals, drop JFR sentinel longs, null-out empty `threadCPUDelta`, omit
  default-zero `eventDelay`. Restore legacy behavior with the matching
  `--full-marker-payload` / config flags
- Default-off `DEFAULT_NOISY_EVENTS` bundle filters high-volume GC/metaspace
  detail events including `jdk.ObjectAllocationInNewTLAB` (superseded by
  `jdk.ObjectAllocationSample` in JDK 16+). Opt back in with
  `--include-noisy-events`. Add `--exclude-event` for ad-hoc filtering
- Add `--max-exec-samples` / `--max-misc-samples` per-thread caps
- Add `--spill-dir` to override the temp directory for spill files
- **Known limitation:** input-side memory (jafar's per-chunk constant pools)
  is not bounded by the spiller. Files dominated by GC-detail events
  (e.g. `jdk.G1HeapRegionTypeChange` on G1, `jdk.GCPhaseParallel`) above
  ~200 MB may need `-Xmx4g`. See README "Memory requirements"

## [0.0.4]

- Ignore stack trace frames with null methods
- Handle virtual threads better
- Use ktlint again for auto formatting in pre-commit hook

## [0.0.3]

### Changed
- Updated dependencies
- Altered interval computation to be closer to the average interval

### Fixed
- Fixed handling of RecordMethods across multiple chunks #6
- Omit line numbers for functions #6