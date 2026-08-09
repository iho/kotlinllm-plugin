# KotlinLLM IntelliJ Plugin

KotlinLLM is an IntelliJ IDEA plugin prototype for experimenting with **Smart macros** in Kotlin/JVM projects. A Smart macro is an explicit Kotlin call whose behavior is backed by generated Kotlin source code. When the running application reaches an unsupported scenario, the plugin can capture the runtime values, ask an LLM agent to generate a narrow implementation update, compile the changed source, and hot-reload the affected class through JDI.

The design goal is that LLM-backed behavior should be explicit at the call site, persistent as source code, and portable as ordinary Kotlin once generated.

## Smart Macros

The public API exposes two Smart macros:

- `asLlm<F, T>(from, hint)` converts a value of type `F` into a value of type `T`.
- `mockLlm<T>()` creates an implementation of interface `T` whose behavior can evolve from runtime interactions.

Unlike direct runtime delegation to an LLM service, KotlinLLM does not call the model on every Smart macro invocation. The LLM is used when generated code cannot handle a scenario yet. Successful behavior is stored in generated Kotlin files and later runs execute that code directly.

## Architecture

KotlinLLM has three layers:

- **Public API**: the stable `asLlm` and `mockLlm` functions used by the target project.
- **Static infrastructure**: managers that route Smart macro calls to generated providers.
- **Dynamic infrastructure**: generated bootstrap, provider, parser, and mock classes maintained by the plugin.

The target project owns both the static API file and the generated source folder. The plugin prepares and updates the dynamic infrastructure before and during application runs.

## Runtime Flow

When a project is launched through the KotlinLLM run configuration:

1. The plugin scans the Kotlin project for `asLlm` and `mockLlm` calls.
2. It creates or updates generated bootstrap, provider, parser, and mock files.
3. It launches the original run configuration under JDI.
4. It registers breakpoints on generated regenerate hooks.
5. If generated logic does not match a runtime scenario, execution reaches a regenerate hook.
6. The plugin captures runtime values and type information from the suspended frame.
7. The LLM agent receives targeted tools for reading values, grepping large inputs, inspecting target types, and submitting a code update.
8. The plugin inserts the update into generated Kotlin source, compiles it, and redefines the loaded class.
9. The original application call is retried against the updated implementation.

## Plugin Development Setup

Requirements:

- IntelliJ IDEA 2025.2.x. The Gradle target is `intellijIdea("2025.2.4")`.
- JDK 21.
- A provider credential saved in the target project's `.kotlinllm` file through `Tools > KotlinLLM Settings`:
  - **OpenAI**: an OpenAI API key.
  - **Grazie**: a Grazie JWT token (or the `GRAZIE_JWT_TOKEN` environment variable).
  - **Anthropic Claude**: an Anthropic API key.
  - **Ollama (local)**: no key required; a running Ollama server (default `http://localhost:11434`).

Build the plugin:

```bash
./gradlew compileKotlin --no-daemon
```

Run tests:

```bash
./gradlew test
```

Run the plugin in a sandbox IDE:

```bash
./gradlew runIde
```

You can also run the Gradle `runIde` task from IntelliJ. Configure the target project's `.kotlinllm` file from `Tools > KotlinLLM Settings`; the plugin reads the API key from that file when it asks Koog/OpenAI for a generated implementation.

## Target Project Setup

The target Kotlin/JVM project must contain the stable Smart macro API file. This repository includes it as [templates/KotlinLLM.kt](templates/KotlinLLM.kt).

Copy it into the `com.jetbrains.kotlinllm` package in the target project. The examples place this file at:

```text
src/main/kotlin/com/jetbrains/kotlinllm/KotlinLlm.kt
```

This `KotlinLlm.kt` file is required for a successful `Run with KotlinLLM`: it defines `asLlm`, `mockLlm`, and the runtime managers used by generated code.

Then use the API from application code:

```kotlin
import com.jetbrains.kotlinllm.asLlm
import com.jetbrains.kotlinllm.mockLlm

val apiUrl: String = asLlm("JetBrains/kotlin", hint = "Return a GitHub issues API URL")
val service: GithubService = mockLlm()
```

`mockLlm<T>()` is intended for interfaces. The plugin generates an implementation class for the interface and evolves method bodies from observed runtime calls.

## Example Projects

- [GithubIssueRadar](examples/GithubIssueRadar) is a standalone Kotlin/JVM sample that uses `asLlm` to derive GitHub issues API URLs, parse GitHub issue JSON, and classify beginner-friendly issue labels. It includes generated KotlinLLM sources so the learned behavior can be inspected and run as ordinary Kotlin. Copy `.kotlinllm.example` to `.kotlinllm` inside the example and fill in `apiKey` when running it through the plugin.

## Initializing KotlinLLM

Each target project needs one generated-source location and one builds folder. Initialize them before the first run:

1. Open the target project in the sandbox IDE.
2. Run `Tools > KotlinLLM Settings`, or use the KotlinLLM toolbar action.
3. Enter the API key, choose an existing Kotlin source root, usually `src/main/kotlin`, and specify the builds folder used for hot-reload class discovery.

The builds folder MUST be specified. KotlinLLM uses it to find compiled class files for hot reload during `Run with KotlinLLM`.

Settings are written to `.kotlinllm` in the target project using project-relative paths, including `generatedFolder` and `buildsFolder`. Use the hidden Advanced section to initialize generated files under:

```text
src/main/kotlin/com/jetbrains/kotlinllm/generated
|-- core
|-- asLlm
`-- mockLlm
```

The generated runtime files in `core` use package `com.jetbrains.kotlinllm.generated.core` and install providers into `AsLlmManager` and `MockLlmManager`. Parser implementations are written under `asLlm` with package `com.jetbrains.kotlinllm.generated.asLlm`, and mock implementations are written under `mockLlm` with package `com.jetbrains.kotlinllm.generated.mockLlm`. The API file loads `com.jetbrains.kotlinllm.generated.core.KotlinLlmBootstrap` lazily on the first `asLlm` or `mockLlm` call, so application code does not need a manual bootstrap call.

You can also right-click a folder in the Project view and choose `Set KotlinLLM Folder`. If a project is missing the generated source root or builds folder, the first `Run with KotlinLLM` attempt prompts for the required settings before the launch can continue.

## Running a Target Application

Create a normal IntelliJ run configuration for the target application first. Then launch it with the custom executor:

- Use `Run with KotlinLLM` from the toolbar or run context menu.
- Do not use the ordinary Run action when you want Smart macro generation.

The plugin owns the launch so it can run before-launch tasks, prepare generated files, attach through JDI, register regenerate-hook breakpoints, compile updates, and redefine classes in the running VM.

During startup the console should show:

```text
KotlinLLM: Running before-launch task 'Build'...
KotlinLLM: Ready. Monitoring for asLlm and mockLlm calls...
```

When execution reaches an unsupported Smart macro scenario, KotlinLLM captures the suspended frame, asks the LLM agent for a source update, compiles the changed generated file, hot-reloads the class, and retries the original application call.

## Generated Files

The plugin creates generated source under:

```text
com.jetbrains.kotlinllm.generated.core
```

Generated files include:

- `KotlinLlmBootstrap`, which installs generated providers into `AsLlmManager` and `MockLlmManager`.
- Generated providers, which dispatch Smart macro calls by `KType`.
- Generated `asLlm` parser classes.
- Generated `mockLlm` implementations.

These files are normal Kotlin source files. Once behavior has been generated, the target project can compile and run that behavior without another LLM request for the same scenario.

## Snapshot Convention (Multi-Agent Spec Generation)

KotlinLLM can run a multi-agent snapshot loop that derives behavior specs, reviews them,
generates mutflow tests, and drives mutation coverage. It is launched from
`Tools > Run Snapshot Orchestration`.

Each scenario is materialized under the generated source root:

```text
<generatedFolder>/snapshots/<scenario-id>/
    snapshot.json        # machine-readable captured state (state map + observed calls)
    snapshot.spec.kt     # SpecAuthor: behavior spec / invariants
    snapshot.review.kt   # SpecReviewer: gaps, counter-examples, feedback
    snapshot.test.kt     # TestGenerator: @MutFlowTest + MutFlow.underTest { } tests
    coverage.md          # MutationAuditor: mutflow killed/survived report
```

The loop runs five role agents (each a Koog `AIAgent` sharing the same provider/model):

| Role | Writes | Purpose |
|------|--------|---------|
| `SpecAuthor` | `snapshot.spec.kt` | Derives behavior specs/invariants from the captured snapshot |
| `SpecReviewer` | `snapshot.review.kt` | Audits the spec for gaps, missing branches, counter-examples |
| `Snapshotter` | `snapshot.json` + review note | Validates/annotates the materialized state |
| `TestGenerator` | `snapshot.test.kt` | Emits `@MutFlowTest` tests wrapping code in `MutFlow.underTest { }` |
| `MutationAuditor` | `coverage.md` | Runs mutflow, reports killed/survived, recommends gap-closing tests |

The orchestrator sequences these agents, invokes mutflow via Gradle on the target project,
and loops back through SpecAuthor/TestGenerator while surviving mutants remain and coverage
is still improving, stopping when all mutants are killed or coverage plateaus.

## Notes

- KotlinLLM targets Kotlin/JVM because the runtime evolution loop depends on JVM class redefinition through JDI.
- Generated updates are intentionally narrow: the LLM agent updates prepared implementation bodies instead of changing arbitrary project files.
- If a generated implementation does not match a new runtime scenario, it should fall through to a regenerate hook rather than silently returning an incorrect value.
- The generated source is part of the project and can be committed when the learned behavior should become portable.

## License

KotlinLLM is licensed under the Apache License 2.0. This keeps the project permissive for plugin users and contributors while preserving copyright notices and providing an explicit patent grant.
