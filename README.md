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
- A test the IDE or the Tooling API launches (`includeTest`, a single test run from an IDE) is treated like `--tests`: the task keeps the tests that run names and the selection does not touch it. The plugin cannot see that request when the task graph is built, so the line for the task may still show a selection; set `TEST_IMPACT=off` to switch the plugin off for such runs.
- `-x testImpactSelect<Task>` leaves the selection out: that `Test` task then runs in full, also when an earlier build left a selection behind.
- Git is read once per build and repository, not once per `Test` task, and the class files of a root are scanned once however many tasks have it on their classpath.
- On a detached HEAD, which is what a pull-request checkout of most CI systems is, the changes are measured only when `baseRef` is set and resolves. Without `baseRef` the whole task runs (`changes-unknown`), because the default candidates are guesses that suit a checked-out branch.
- Changes in other modules are found through the conventional layout `src/<set>/java`, `src/<set>/kotlin` and `src/<set>/resources`. A changed source file whose classes the task's class graph does not know, such as a test of another module, does not affect that task. A Java or Kotlin file outside every known layout runs the full task, and so does any resource change or a change of build logic.
- Only `Test` tasks that exist after the project is evaluated are wired. A task created later runs as without the plugin.

### Mode

`mode` is `auto` (default), `full` or `off`. The first of these that is set wins:

1. the Gradle property `-PtestImpact=<mode>`
2. the environment variable `TEST_IMPACT=<mode>`
3. `testImpact { mode = "<mode>" }`
4. `auto`

- `auto` selects.
- `full` runs every test and still prints the line, so builds can be compared.
- `off` adds nothing to the build: no task, no task input, no action. Gradle behaves exactly as without the plugin, including build cache hits.

The value is trimmed and not case-sensitive. A value that is none of these fails the build with a message naming where it came from.

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
- Project outputs that a build step transforms into the Gradle user home are not scanned.
- Frameworks that find tests by scanning the classpath, such as Cucumber or custom runners, are not understood.

## Status

Early development. Every Test task of a project that applies the plugin runs only the selected classes; `mode = "off"` restores the stock behaviour.

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
