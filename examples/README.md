# DICE Examples

Focused examples of the DICE API. Each module shows how to use part of the library.
The module table and individual READMEs say which stores each example uses.

Examples are written in Java. A Kotlin example appears where Kotlin has its own entry point, such
as a typed `ContextId` or a builder block.

The examples replay recorded model replies. They need no model API key.

## Modules

| Module | What it shows |
|---|---|
| [`examples-java`](examples-java) | DICE wired with Spring Boot: a schema, extraction on an event, storage and scoped queries. Start here. |
| [`examples-kotlin`](examples-kotlin) | The parts of the same API that read differently from Kotlin. |
| [`examples-decision-revision`](examples-decision-revision) | Showcase: an Embabel decision chooses how a new fact revises stored ones. |
| [`examples-backend-swap`](examples-backend-swap) | The same memory desk on the in-memory store, Neo4j, and SQLite. |
| [`examples-governed-chat`](examples-governed-chat) | Showcase: admission, pinned policy, a prompt budget, and an audit trail for chat memory. |
| [`examples-common`](examples-common) | Test support the examples share: the scripted model and its recorded replies. |
| [`examples-graph-reasoning`](examples-graph-reasoning) | Graph traversal and a Prolog approval rule that cites recorded claims. |
| [`examples-multi-context-memory`](examples-multi-context-memory) | Recall across a support session and handbook under a scope and budget set by the host. |
| [`examples-support-domain`](examples-support-domain) | A support desk turns chats into propositions and graph relationships that cite their sources. |
| [`examples-corrections-history`](examples-corrections-history) | How DICE handles repeated claims, corrections, and changes over time. |
| [`examples-mcp-desktop`](examples-mcp-desktop) | Showcase: DICE memory as MCP tools for a desktop client. |

Each module has its own README. The tests are the examples.

## Build

This build sits outside the library reactor and uses the DICE installed from this repository. Maven
takes the newest copy of a snapshot, so install the library right before building the examples
and they use that install.
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

Use JDK 21. Run Docker to include the Neo4j tests. Testcontainers skips those tests when Docker
is unavailable.

The Build workflow builds the examples right after the library, on every pull request and every
push. A change to the library's public API that breaks an example fails there, so fix the example
in the same change.

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
| DICE | The version in the root `pom.xml`, repeated as `dice.version` |
| Kotlin | `2.2.21`, the version DICE is built with, repeated as `kotlin.version` |
| Embabel Agent | `1.5.4`, the version DICE compiles against, repeated as `embabel-agent.version` |
| Spring Boot | `4.1.1` |
| Spring Modulith | `2.1.0` |
| jMolecules | `2025.0.2` |
| Java | 21 |

Every module's build compares those three with the library's and stops when one differs. A
release moves the root version, so set `dice.version` to match afterwards. The Build workflow
checks the versions in `examples/pom.xml` as they are, except on the release plugin's own two
commits, and builds the examples against the root version so those commits build too.
