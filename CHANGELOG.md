# jfrtofp

## Unreleased

- Fix handling of files without execution samples
  - (see https://github.com/parttimenerd/intellij-profiler-plugin/issues/30)
- Add `--execution-sample-type` to specify the type of execution samples to use

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