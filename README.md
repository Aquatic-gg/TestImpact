# TestImpact

A Gradle plugin that runs only the tests a change can reach.

## Problem

In a large build, any change reruns every test. Most of those tests cannot be affected by the change, so the wait is wasted.

## Promise

TestImpact selects the tests that can reach what changed. It does this from the compiled bytecode dependencies plus what bytecode cannot show. The selection is deterministic and code-driven: the same inputs always give the same tests. It needs no network access and uses no AI.

## Guarantees

- When the analysis is uncertain, it runs everything.
- A full run is always available and is never removed.
- Every selection can be explained: you can see why a test was chosen or skipped.

## Not guaranteed

Some links between code and tests do not show up in bytecode, so selection can miss them:

- Tests that reach code only through reflection with computed names.
- External state such as databases, files outside the build, and the environment.
- Non-JVM tests.

Run the full suite on your main branch and before a release. Treat selection as a fast feedback loop, not a replacement for the full run.

## Status

Early development. The plugin applies, but it selects nothing yet, so every test still runs.

## Requirements

- Gradle 9.0 or newer
- JDK 17 or newer

## Analysis cache

Class files are analysed once and the result is cached in `build/testimpact/class-cache` under the root project directory, keyed by the content of each class file. A cache written by another plugin version, or a damaged file, is rebuilt without a warning. A cache file unused for 7 days is deleted, and `clean` removes the whole directory.

## Build

```
./gradlew build
```

## License

Apache License 2.0. See [LICENSE](LICENSE).
