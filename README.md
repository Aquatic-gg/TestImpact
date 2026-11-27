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

## How tests are selected

For every test task the plugin reads the compiled classes on the task's runtime classpath and builds a dependency graph from them. A change is mapped to the classes compiled from the changed file, and every test class that can reach one of them is selected.

- The graph follows project dependencies, test fixtures and custom test source sets, because it is built from the resolved test runtime classpath. Libraries from the Gradle user home and the local Maven repository are not scanned.
- A test that inherits from an abstract base class or interface is selected when the base changes, also when the base lives in another module or in test fixtures. An abstract base class or interface is never selected itself, its concrete subclasses are; a concrete base class is selected like any other test class.
- A change reached through a nested, inner or anonymous class selects the top-level test class that contains it.
- JUnit 4, JUnit Jupiter, the Vintage engine and Kotest are supported. A global extension or listener registration, or a Kotest project or package config (also through a base class), that a change reaches selects every test of the task.
- The result is "unknown", and the whole task runs, when the change cannot be mapped to classes (build logic, resources, unresolved sources, a changed source file that declares no classes such as `package-info.java`, unknown change detection), when the framework is TestNG or another runner, when a JUnit Platform engine is missing or not one of the supported ones, or when a class file or service file cannot be read.

Known gaps:

- Constants and inline function bodies of a project that is only a `compileOnly` dependency are folded into the class using them, so a change to them can be missed.
- Project outputs that a build step transforms into the Gradle user home are not scanned.
- Frameworks that find tests by scanning the classpath, such as Cucumber or custom runners, are not understood.

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
