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

## Usage

```kotlin
plugins {
    id("gg.aquatic.testimpact")
}
```

That is all the setup. Apply the plugin in every project whose tests should be selected, for example from a convention plugin. Each `Test` task of that project, including custom test suites, gets a `testImpactSelect<Name>` task that runs before it, decides which test classes the changes can reach and prints one line:

```
TestImpact :app:test: changed 3, selected 5 of 120 test classes
TestImpact :app:integrationTest: changed ?, selected all test classes, full: changes-unknown: <detail>
```

- Changes are the files that differ from the merge base with `baseRef`, committed or not, plus untracked files. A tree without changes selects nothing, so the tasks are skipped, unless `alwaysRun` names classes.
- A task whose selection is empty is skipped, the build still succeeds.
- Anything uncertain runs the whole task and the line says why after `full:`. A failure inside the plugin does the same.
- The selection is an input of the `Test` task, so a partial run is never reused as the result of a full one, from the up-to-date check or the build cache.
- `--tests` on the command line wins: the task runs what `--tests` names and nothing is selected away. `--tests` given for any task of the build makes every `Test` task of the build run in full, because the plugin cannot tell which task the option is meant for. A `filter { }` in the build script stays in force together with the selection; classes it excludes are not run and an empty intersection does not fail the build.
- A test the IDE or the Tooling API launches (`includeTest`, a single test run from an IDE) is treated like `--tests`: the task keeps the tests that run names and the selection does not touch it. The plugin cannot see that request when the task graph is built, so the line for the task may still show a selection; set `TEST_IMPACT=off` to switch the plugin off for such runs. A filter added after the configuration, other than through `--tests`, makes the `Test` task run in full (the filter's tests, not the selection), and the build logs one `TestImpact <task>: the test filter was changed after configuration` line for it. `--tests` is not such a filter and logs no such line.
- `-x testImpactSelect<Task>` leaves the selection out: that `Test` task then runs in full, also when an earlier build left a selection behind.
- Git is read once per build and repository, not once per `Test` task, and the class files of a root are scanned once however many tasks have it on their classpath.
- On a detached HEAD, which is what a pull-request checkout of most CI systems is, the changes are measured only when `baseRef` is set and resolves. Without `baseRef` the whole task runs (`changes-unknown`), because the default candidates are guesses that suit a checked-out branch.
- Changes in other modules are found through the conventional layout `src/<set>/java`, `src/<set>/kotlin` and `src/<set>/resources`. A changed source file whose classes the task's class graph does not know, such as a test of another module, does not affect that task. A Java or Kotlin file outside every known layout runs the full task, and so does a resource change in a resource folder of the task's own project or a change of build logic. A resource folder that another module adds outside the conventional layout (for example `srcDir("config")` in a library) is not known to the task; see Known gaps. Build logic is a build script, `buildSrc`, `build-logic` and `gradle` next to the settings file, and any file of a nested build below it, that is a directory with its own settings file such as an included build.
- Only `Test` tasks that exist after the project is evaluated are wired. A task created later runs as without the plugin.

### Mode

`mode` is `auto` (default), `full` or `off`. The first of these that is set wins:

1. the Gradle property `-PtestImpact=<mode>`
2. the environment variable `TEST_IMPACT=<mode>`
3. `testImpact { mode = "<mode>" }`
4. `auto`

- `auto` selects.
- `full` runs every test and still prints the line, so builds can be compared.
- `off` adds no selection task, input or action to the `Test` tasks. Gradle behaves as without the plugin for them, including build cache hits. `testImpactReport` stays available and reports `MODE_OFF`.

The value is trimmed and not case-sensitive. A value that is none of these fails the build with a message naming where it came from.

### Red runs

A selected run that is green proves little when an earlier run was red. The plugin remembers the failures of every `Test` task and follows them up before it trusts a green result.

- **Memory.** When a run of a `Test` task fails, the failing classes are written to `build/testimpact/escalation/<task>.state`, and a full run is owed. A crashed test JVM or a build that dies mid-run counts the same, even when no class can be named.
- **Quick phase.** In mode `auto`, with a full run owed, `test` first runs its companion task `testImpactQuick<Name>` (`testImpactQuickTest` for `test`). It runs the red classes and the classes the selection picked, and nothing else.
- **Stop on red.** When the quick phase is still red, `test` fails before the full run, naming the classes, the report of the quick task and how to skip it. This holds under `--continue` and `--parallel`.
- **Full run.** When the quick phase is green, `test` runs every class. Only a green full run clears the memory. A task that is owed a full run is never up to date and never taken from the build cache, and the result of the quick task is never cached.
- **Launchers and `--tests`.** A run that names tests (`--tests`, an IDE or Tooling API launch of single tests) runs exactly those, skips the quick phase, and leaves the memory as it was.
- **`mode`.** `full` skips the quick phase and runs every test. `off` adds no quick task and no state file.

The state file is plain text, one `key=value` line each, UTF-8, with the header `testimpact-escalation 1`:

```
testimpact-escalation 1
full-run-required=true
unknown-failure=false
last-phase=quick
last-result=green
red=app.CalcTest
```

`last-phase` is `quick`, `partial` (a run narrowed by `--tests` or by the selection) or `full`; `last-result` is `running`, `green` or `red`. Only `full-run-required=false` with `last-phase=full` and `last-result=green` is a verdict, so a build stopped during a phase leaves `last-result=running` and the full run owed. A file that is damaged, too large, of another version or with a class name that is not a Java name counts as a full run owed, with one warning. `clean` deletes the file and so the memory.

`testImpactQuick<Name>` is a `Test` task that copies the settings of the task it follows through public API: JVM arguments, system properties, environment, heap size, classpath and test classes, test framework and its options, includes, excludes, filters, forks and the Java launcher, and the task's `dependsOn`. It does not copy `doFirst` or `doLast` actions, listeners, services or properties set through build-specific code, so a test that needs one of those can fail in the quick phase only; the verdict always comes from the task itself, and `-x testImpactQuick<Name>` skips the quick phase. A `useJUnitPlatform()` that the build script applies to the task after the quick task was created is not seen by the quick task, which is created when the task graph is made.

## Configuration

```kotlin
testImpact {
    mode = "auto"
    baseRef = "origin/main"
    wideChangePercent = 60
    wideChangeMinClasses = 12
    dynamicPackages.add("com.example.plugins")
    alwaysRun.add("*IntegrationTest")
}
```

- `mode`: see [Mode](#mode).
- `baseRef`: the git ref the changes are measured against. Default: not set, the first of `origin/HEAD`, `origin/main`, `origin/master`, `main` and `master` that exists; none of them, or no git repository, runs the full task. An empty value counts as not set. At most 255 characters, no whitespace or control characters, no leading `-`, and no `..` at either end.
- `wideChangePercent`, `wideChangeMinClasses`: when the selection holds more than `wideChangePercent` percent (1 to 100, default 60) of at least `wideChangeMinClasses` test classes (0 to 1,000,000, default 12), the whole task runs instead, with the reason `wide-change`.

- `dynamicPackages`: packages whose classes are loaded or called by name in ways bytecode cannot show. A class counts as reflecting when it calls `Class.forName` or another `Class` or `ClassLoader` lookup by name, `ServiceLoader`, `MethodHandles.Lookup`, `java.lang.reflect` (except `Array`), `kotlin.reflect.full` / `kotlin.reflect.jvm`, or `call` / `callBy` on any `kotlin.reflect` type. Such a class is assumed to reach its own package and the packages below it. A change in a listed package, or below it, reaches every class that uses reflection, wherever it lives.
- `alwaysRun`: test classes that run whatever changed. A pattern is made of letters, digits, `_`, `$`, `.` and `*`, where `*` matches any sequence including dots. A pattern without a dot matches the simple class name.

`dynamicPackages` and `alwaysRun` accept at most 1000 entries of at most 512 characters. Invalid values of any property fail the build with a message naming the property and the value.

## How tests are selected

For every test task the plugin reads the compiled classes on the task's runtime classpath and builds a dependency graph from them. A change is mapped to the classes compiled from the changed file, and every test class that can reach one of them is selected.

- The graph follows project dependencies, test fixtures and custom test source sets, because it is built from the resolved test runtime classpath. Libraries from the Gradle user home and the local Maven repository are not scanned.
- A test that inherits from an abstract base class or interface is selected when the base changes, also when the base lives in another module or in test fixtures. An abstract base class or interface is never selected itself, its concrete subclasses are; a concrete base class is selected like any other test class.
- A change reached through a nested, inner or anonymous class selects the top-level test class that contains it.
- Links that bytecode cannot show are followed too: a class name in a string constant or a scanned resource, a `META-INF/services` file, and the classes of a listed `dynamicPackages` package. The record of a selected test in the result file of the task (`build/testimpact/selection/<task>.txt`) names the kind of link it went through; the console line does not print it.
- Test classes matching `alwaysRun` are selected in every automatic run, also when nothing changed.
- JUnit 4, JUnit Jupiter, the Vintage engine and Kotest are supported. A global extension or listener registration, or a Kotest project or package config (also through a base class), that a change reaches selects every test of the task.
- The result is "unknown", and the whole task runs, when the change cannot be mapped to classes (build logic, resources, unresolved sources, a changed source file that declares no classes such as `package-info.java`, unknown change detection), when the framework is TestNG or another runner, when a JUnit Platform engine is missing or not one of the supported ones, or when a class file or service file cannot be read.

Known gaps:

- Constants and inline function bodies of a project that is only a `compileOnly` dependency are folded into the class using them, so a change to them can be missed.
- A resource folder another module adds outside the conventional layout (for example `srcDir("config")` in `:lib`) is not classified: a change there does not select the tests of `:app` that read the file from the `lib` jar, in the build and in the report alike.
- Project outputs that a build step transforms into the Gradle user home are not scanned.
- Frameworks that find tests by scanning the classpath, such as Cucumber or custom runners, are not understood.

## Report

`testImpactReport` shows what would be selected for the current changes, and why. For every Test task of its project it runs, at execution time, the same decision as the task's `testImpactSelect<Task>`: the change detection against the task's own source roots, the selection, `mode`, `--tests` and the wide-change rule, with the settings of the `testImpact` block. It writes the result and runs no test.

```
./gradlew testImpactReport
./gradlew testImpactReport --base main
./gradlew testImpactReport --explain com.example.FooTest
```

- `--base <ref>` compares with that git ref instead of the `baseRef` of the `testImpact` block, or the default bases when that is not set. It is checked like `baseRef` (at most 255 characters, no whitespace or control characters, no leading `-`, no `..` at either end), and an invalid ref fails the task. The report applies the same `alwaysRun` and `dynamicPackages` rules and the same resource links as the selection of a build; in `report.txt` a hop through such a link is named by its seed kind (for example `SERVICE`), in `report.json` by `path[].seed`.
- `--explain <class>` prints, for each Test task of the project, the chain from the changed file to the class, the rule that forced a full run, or that the class is not selected. A nested class is explained by its top-level class. An invalid class name fails the task.
- `changes` are the files the first task (by path) read; every task of the project reads the same files. `forced` is set when every task that read them is forced to a full run by the same rule. When no task read the changes, because the project has no Test task or `mode` or `--tests` decided every task first, `changes.state` is `unknown` with the reason `NOT_ANALYSED`. In mode `off` each task is reported as a full run with the rule `MODE_OFF`.
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
tasks[].counts.recognised | number | candidates identified as tests by framework markers, over all candidate classes of the test roots
tasks[].counts.candidates | number | concrete top-level classes of the test roots
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
tasks[].tests[].path[].seed | string? | for a hop through a link bytecode does not show: SERVICE, CLASS_NAME, PACKAGE_NAME, DESCRIPTOR, RESOURCE_NAME, RESOURCE_LOADER or DYNAMIC
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

Reason codes of `forced.reason` and `tasks[].rule.reason`: `CHANGES_UNKNOWN`, `CHANGE_NOT_MAPPED`, `UNSUPPORTED_FRAMEWORK`, `UNKNOWN_ENGINE`, `GRAPH_UNKNOWN`, `ANALYSIS_ERROR`. `tasks[].rule.reason` can also be a rule of the decision after the selection: `WIDE_CHANGE`, `NOT_RECOGNISED`, `MODE_FULL`, `MODE_OFF`, `USER_TESTS`, `PLUGIN_ERROR`. Codes of `changes.reason`: `NOT_A_REPOSITORY`, `GIT_UNAVAILABLE`, `GIT_FAILED`, `NO_HEAD_COMMIT`, `DETACHED_HEAD`, `SHALLOW_CLONE`, `BASE_REF_NOT_FOUND`, `NO_MERGE_BASE`, `ANALYSIS_ERROR`, `NOT_ANALYSED`.

New fields and new codes may appear without a new version: ignore fields you do not know and treat a code you do not know as a full run. Removing or renaming a field, or changing what it means, raises `schemaVersion`.

## Status

Early development. Every Test task of a project that applies the plugin runs only the selected classes; `mode = "off"` restores the stock behaviour. The `testImpactReport` task shows what would be selected. A red run is followed by a quick run of the failed classes and then a full run; see [Red runs](#red-runs).

## Requirements

- Gradle 9.0 or newer (9.1 or newer when Gradle itself runs on JDK 25)
- JDK 17 or newer

The tests run on JDK 17, 21 and 25 against three Gradle versions: the oldest supported one, the last release of the previous minor line and the newest stable release. The versions are listed in `gradle/libs.versions.toml`. When a new minor line is released, the newest version moves and the previous one becomes the last release of the line before it. Once a new major version is stable, the list holds the newest release of each of the last two majors. The floor moves only in a plugin minor release, with a note. Gradle 8 is not supported: the Kotlin embedded in Gradle 9.0 is the lowest the plugin's API can rely on.

Behaviour on these versions:

- The configuration cache is supported, and the selection is computed when the build runs, so an edit between two builds changes it even when the cache entry is reused.
- Isolated projects are compatible.
- The selection is part of the cache key of each `Test` task: a clean build with the same selection restores the task from the build cache, also in another checkout, and a different selection runs it.
- `testImpactSelect<Task>` and `testImpactReport` are never up to date or cached and run on every build.

## Analysis cache

Class files are analysed once and the result is cached in `build/testimpact/class-cache` under the build's settings directory, keyed by the content of each class file. A cache written by another plugin version, or a damaged file, is rebuilt without a warning. A cache file unused for 7 days is deleted. To clear the cache, delete that directory; a `clean` task of the root project does not necessarily remove it.

## Build

```
./gradlew build
```

`./gradlew build -Ptestimpact.testJava=21` runs the tests on another JDK (17 to 25; a newer JDK needs its Gradle floor added to `CompatMatrix` first).

## License

Apache License 2.0. See [LICENSE](LICENSE).
