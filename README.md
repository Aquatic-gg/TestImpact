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
- Reflection on classes outside the package of the reflecting class that is not listed in `dynamicPackages`.
- External state such as databases, files outside the build, and the environment.
- Non-JVM tests.

Run the full suite on your main branch and before a release. Treat selection as a fast feedback loop, not a replacement for the full run.

## Configuration

```kotlin
testImpact {
    dynamicPackages.add("com.example.plugins")
    alwaysRun.add("*IntegrationTest")
}
```

- `dynamicPackages`: packages whose classes are loaded or called by name in ways bytecode cannot show. A class counts as reflecting when it calls `Class.forName` or another `Class` or `ClassLoader` lookup by name, `ServiceLoader`, `MethodHandles.Lookup`, `java.lang.reflect` (except `Array`), `kotlin.reflect.full` / `kotlin.reflect.jvm`, or `call` / `callBy` on any `kotlin.reflect` type. Such a class is assumed to reach its own package and the packages below it. A change in a listed package, or below it, reaches every class that uses reflection, wherever it lives.
- `alwaysRun`: test classes that run whatever changed. A pattern is made of letters, digits, `_`, `$`, `.` and `*`, where `*` matches any sequence including dots. A pattern without a dot matches the simple class name.

Both accept at most 1000 entries of at most 512 characters. Invalid entries fail the build with a message naming the property and the entry. The values take effect once selection runs; today the plugin selects nothing.

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

## Report

`testImpactReport` shows what would be selected for the current changes, and why. It runs the change detection and the selection for every Test task of its project at execution time and writes the result; it runs no test.

```
./gradlew testImpactReport
./gradlew testImpactReport --base main
./gradlew testImpactReport --explain com.example.FooTest
```

- `--base <ref>` compares with that git ref instead of the default bases. The ref has at most 256 characters.
- `--explain <class>` prints, for each Test task of the project, the chain from the changed file to the class, the rule that forced a full run, or that the class is not selected. A nested class is explained by its top-level class. An invalid class name fails the task.
- The files are `build/reports/testimpact/report.json` and `report.txt` of the project. The report lists only what the selection returns: a class that is not selected is not listed, so `--explain` says "not selected" for a class that no change reaches and for a name that is not a test class of the task.
- All paths are relative to the repository. Absolute paths in free text are shown as `<root>` (the settings directory), `<gradle-user-home>` and `<home>`.
- The report is deterministic: two runs on the same tree give the same `report.json` except the `durationsMs` values.

Sample `report.txt`:

```
base main 0123456 merge-base 0123456: 1 changed files
  a/src/main/java/a/Core.java
:c:test selected 1 of 2 test classes (2 recognised) in 12 ms
  c.ServiceTest <- a/src/main/java/a/Core.java: a.Core <-REFERENCE- b.Service <-REFERENCE- c.ServiceTest$Inner
:f:test full run: a JUnit Platform engine is missing or not supported (UNKNOWN_ENGINE): f.FakeEngine
analysis 85 ms (detection 60 ms, selection 25 ms)
```

### JSON schema, version 1

One line per field: path, type, meaning. `[]` marks the elements of an array, `?` a value that is `null` when it does not apply. Every field is always present.

```text json-schema-v1
schemaVersion | number | layout version of this file, 1
project | string | path of the project, `:` for the root
changes | object | what changed
changes.state | string | `known` or `unknown`
changes.baseRef | string? | the ref compared with
changes.baseCommit | string? | commit of the base ref
changes.mergeBase | string? | merge base of the base and HEAD
changes.reason | string? | why the changes are unknown
changes.detail | string? | detail of the reason
changes.files | array | changed files, sorted by path
changes.files[].path | string | repository-relative path
changes.files[].status | string | ADDED, MODIFIED, DELETED, RENAMED or UNTRACKED
changes.files[].kind | string | SOURCE, RESOURCE, BUILD_LOGIC or OTHER
changes.files[].classes | array | classes compiled from the file
changes.files[].classes[] | string | binary class name
forced | object? | the rule that forces a full run of every task
forced.reason | string | reason code
forced.text | string | sentence for the reason
forced.detail | string | what triggered it
tasks | array | Test tasks of the project, sorted by path
tasks[].path | string | task path
tasks[].result | string | `selected` or `full`
tasks[].rule | object? | why the task runs in full
tasks[].rule.reason | string | reason code
tasks[].rule.text | string | sentence for the reason
tasks[].rule.detail | string | what triggered it
tasks[].counts | object? | set when the result is `selected`
tasks[].counts.selected | number | selected test classes
tasks[].counts.recognised | number | selected classes recognised as tests
tasks[].counts.candidates | number | test classes of the task
tasks[].tests | array | selected test classes, sorted by name; empty for a full run
tasks[].tests[].className | string | binary class name
tasks[].tests[].reason | string | REACHED or GLOBAL
tasks[].tests[].recognised | boolean | the class is recognised as a test class
tasks[].tests[].changedFile | string | the changed file that started the path
tasks[].tests[].seedClass | string | the class compiled from the changed file where the path starts
tasks[].tests[].reachedClass | string | the class reached: the test class or a class nested in it; for GLOBAL the class registering the extension
tasks[].tests[].path | array | edges from the seed class outward
tasks[].tests[].path[].from | string | the dependent class
tasks[].tests[].path[].to | string | the class it depends on
tasks[].tests[].path[].kind | string | SUPERTYPE, REFERENCE, INLINE, CONSTANT or TYPEALIAS
tasks[].durationsMs | object | time spent on the task
tasks[].durationsMs.selection | number | milliseconds
totals | object | sums over the tasks
totals.tasks | number | Test tasks
totals.fullRunTasks | number | tasks that run in full
totals.selectedTests | number | selected test classes of the tasks with a selection
totals.candidates | number | test classes of the tasks with a selection
durationsMs | object | time spent on the analysis
durationsMs.detection | number | milliseconds
durationsMs.selection | number | milliseconds
durationsMs.total | number | milliseconds
```

Reason codes of `forced.reason` and `tasks[].rule.reason`: `CHANGES_UNKNOWN`, `CHANGE_NOT_MAPPED`, `UNSUPPORTED_FRAMEWORK`, `UNKNOWN_ENGINE`, `GRAPH_UNKNOWN`, `ANALYSIS_ERROR`. Codes of `changes.reason`: `NOT_A_REPOSITORY`, `GIT_UNAVAILABLE`, `GIT_FAILED`, `NO_HEAD_COMMIT`, `DETACHED_HEAD`, `SHALLOW_CLONE`, `BASE_REF_NOT_FOUND`, `NO_MERGE_BASE`, `ANALYSIS_ERROR`.

New fields and new codes may appear without a new version: ignore fields you do not know and treat a code you do not know as a full run. Removing or renaming a field, or changing what it means, raises `schemaVersion`.

## Status

Early development. The `testImpactReport` task shows what would be selected; the Test tasks still run everything.

## Requirements

- Gradle 9.0 or newer
- JDK 17 or newer

## Analysis cache

Class files are analysed once and the result is cached in `build/testimpact/class-cache` under the build's settings directory, keyed by the content of each class file. A cache written by another plugin version, or a damaged file, is rebuilt without a warning. A cache file unused for 7 days is deleted. To clear the cache, delete that directory; a `clean` task of the root project does not necessarily remove it.

## Build

```
./gradlew build
```

## License

Apache License 2.0. See [LICENSE](LICENSE).
