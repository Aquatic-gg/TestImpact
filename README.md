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
- Class names inside resources that are not scanned. Scanned are files under `META-INF/` (except `.kotlin_module` and signature files `.SF`, `.RSA`, `.DSA`, `.EC`) and files with the extension `yml`, `yaml`, `properties`, `json`, `xml`, `conf`, `toml`, `txt` or `mf`; other resources count by path only.
- Reflection on classes outside the package of the reflecting class that is not listed in `dynamicPackages`.- External state such as databases, files outside the build, and the environment.
- Non-JVM tests.

Run the full suite on your main branch and before a release. Treat selection as a fast feedback loop, not a replacement for the full run.

## Configuration

```kotlin
testImpact {
    dynamicPackages.add("com.example.plugins")
    alwaysRun.add("*IntegrationTest")
}
```

- `dynamicPackages`: packages whose classes are loaded or called by name in ways bytecode cannot show. A class counts as reflecting when it calls `Class.forName` or another `Class` or `ClassLoader` lookup by name, `ServiceLoader`, `MethodHandles.Lookup`, `java.lang.reflect`, or `kotlin.reflect.full` / `kotlin.reflect.jvm`. Such a class is assumed to reach its own package and the packages below it. A change in a listed package, or below it, reaches every class that uses reflection, wherever it lives.
- `alwaysRun`: test classes that run whatever changed. A pattern is made of letters, digits, `_`, `$`, `.` and `*`, where `*` matches any sequence including dots. A pattern without a dot matches the simple class name.

Both accept at most 1000 entries of at most 512 characters. Invalid entries fail the build with a message naming the property and the entry. The values take effect once selection runs; today the plugin selects nothing.

## Status

Early development. The plugin applies, but it selects nothing yet, so every test still runs.

## Requirements

- Gradle 9.0 or newer
- JDK 17 or newer

## Build

```
./gradlew build
```

## License

Apache License 2.0. See [LICENSE](LICENSE).
