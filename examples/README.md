# DICE Examples

Focused examples of the DICE API. Each one shows a single way of using the library and runs on
the in-memory stores. They are not full applications, and they are never published.

Examples are written in Java. A Kotlin example appears where Kotlin has its own entry point, such
as a typed `ContextId` or a builder block.

The model's replies are recorded in the repository and replayed by a scripted model, so the build
needs no API key and no running service.

## Modules

| Module | What it shows |
|---|---|
| [`examples-java`](examples-java) | DICE wired with Spring Boot: a schema, extraction on an event, storage and scoped queries. Start here. |
| [`examples-kotlin`](examples-kotlin) | The parts of the same API that read differently from Kotlin. |
| [`examples-common`](examples-common) | Test support the examples share: the scripted model and its recorded replies. |

Each module has its own README. The tests are the examples.

## Build

This build sits outside the library reactor and uses the DICE installed from this repository. It does not download DICE snapshots.
From the repository root, install the library, then build the examples:

```bash
./mvnw -B install -DskipTests
```

```bash
./mvnw -B -f examples/pom.xml verify
```

Build one example and what it depends on:

```bash
./mvnw -B -f examples/pom.xml -pl examples-java -am verify
```

JDK 21 is the only prerequisite.

CI builds the examples on every pull request and every push to main. A change to the library's
public API that breaks an example fails there, so fix the example in the same change.

## Offline model

`examples-common` holds `ScriptedChatModel`, a Spring AI `ChatModel` that answers from rules of
the form "when the prompt contains this text, reply with that". It is registered with Embabel
Agent as the default model. DICE renders its extraction prompt and parses the reply as it does
with a live model.

Recorded replies are JSON files under each module's `src/test/resources/recorded/`, one per model
call.

## Versions

| Dependency | Version |
|---|---|
| DICE | The version in the root `pom.xml`, repeated as `dice.version` in `examples/pom.xml`. The build stops when the two differ. |
| Kotlin | `2.2.21`, the version DICE is built with |
| Embabel Agent | `1.5.4` |
| Spring Boot | `4.1.1` |
| Spring Modulith | `2.1.0` |
| jMolecules | `2025.0.2` |
| Java | 21 |
