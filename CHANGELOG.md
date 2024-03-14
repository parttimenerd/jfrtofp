# jfrtofp

## Unreleased

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