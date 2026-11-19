# MCP desktop

DICE memory served as MCP tools that a desktop MCP client can call. The app is a small Spring
Boot server. `DemoMemory` creates an in-memory store holding three facts about a customer called
Acme, all in the context `demo`. DICE's MCP auto-configuration turns that store into tools and
serves them over streamable HTTP under the server name `dice-memory`.

Out of the box the tools only read. A client can recall, list and fetch facts, but cannot store
new ones. Restarting the app starts over from the three demo facts.

## Run

From the repository root, install the library, then the examples this one uses:

```bash
./mvnw -B install -DskipTests
./mvnw -B -f examples/pom.xml -pl examples-mcp-desktop -am install -DskipTests
```

Then start the server:

```bash
./mvnw -B -f examples/pom.xml -pl examples-mcp-desktop spring-boot:run
```

The server listens at `http://localhost:8080/mcp` and keeps running until you stop it. It binds
`127.0.0.1` by default, so only clients on the same machine can reach it.

To turn on `dice_store`, start it with writes enabled:

```bash
./mvnw -B -f examples/pom.xml -pl examples-mcp-desktop spring-boot:run \
    -Dspring-boot.run.arguments=--embabel.dice.mcp.writes-enabled=true
```

## Connect a desktop client

Many desktop clients start their MCP servers as local commands that talk over stdio.
[`desktop-client.json`](desktop-client.json) bridges stdio to the server's streamable HTTP
endpoint with `mcp-remote`. Merge it into your client's MCP server settings:

```json
{
  "mcpServers": {
    "dice-memory": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "http://localhost:8080/mcp"]
    }
  }
}
```

A client that speaks streamable HTTP itself needs no bridge. Point it at
`http://localhost:8080/mcp` directly.

Ask the client what DICE remembers about Acme in the context `demo`, and it should call
`dice_recall` or `dice_list` and answer with the three facts.

The agent platform will not start without a model, so `NoModelConfiguration` registers a
placeholder. It has an empty script, answers nothing, and any prompt sent to it fails. The tools
never call a model, so they do not need a real one. The model that does the talking is the
client's own.

## Tools

| Tool | Arguments | Returns | Access |
|---|---|---|---|
| `dice_recall` | `contextId`, optional `query`, optional `limit` (default 10, at most 100) | The facts in the context that match the query, by semantic and keyword search. Without a query, every fact ordered by confidence. | Read-only |
| `dice_list` | `contextId`, optional `limit` (default 10, at most 100) | `Found N memories in context '...':` and one line per fact with its id and confidence, or `No memories in context '...'.` | Read-only |
| `dice_get` | `contextId`, `propositionId` | One fact with its id, status and text. An id that is missing and an id from another context get the same answer. | Read-only |
| `dice_store` | `contextId`, `text`, optional `confidence` (default 0.8) | `Stored proposition <id>: <text>`. The fact is stored as given, with no extraction. | Opt-in, with `embabel.dice.mcp.writes-enabled=true` |

By default recall and list leave out facts below confidence 0.5.

## Keep it on this machine

`contextId` is a scope the caller picks, not a credential. It keeps one call inside one context,
but any caller can name any context. This server checks no identity, so it binds `127.0.0.1` by
default and only accepts connections from this machine. Listening wider, for example with
`--server.address=0.0.0.0`, lets anyone who can reach the port list the memories and, with writes
on, store new ones. Only do that when the host puts its own authorization in front of it.

## Behaviour the tests assert

The tests start the app on a random local port and talk to it with a real MCP client over HTTP.

| Test | Behaviour |
|---|---|
| `ReadOnlyServerTests.introducesItselfAsDiceMemory` | The server names itself `dice-memory` in the handshake. |
| `ReadOnlyServerTests.offersTheReadToolsButNotStore` | With the app's own settings the client sees `dice_recall`, `dice_list` and `dice_get`, and no `dice_store`. |
| `ReadOnlyServerTests.recallsTheAcmeFactWhenAskedAboutTheProPlan` | Recalling "Pro plan" in `demo` returns "Acme is on the Pro plan". |
| `ReadOnlyServerTests.listsAllThreeDemoFacts` | Listing `demo` returns the three demo facts, each with `confidence=0.90`. |
| `ReadOnlyServerTests.fetchesAListedFactByItsId` | The id on a listed fact fetches that fact with `dice_get`, with status `ACTIVE`. |
| `ReadOnlyServerTests.seesNoMemoriesInAnotherContext` | List and recall in the context `elsewhere` find nothing and say the context is empty. |
| `WritableServerTests.introducesItselfAsDiceMemory` | With writes on, the server still names itself `dice-memory`. |
| `WritableServerTests.offersStoreAlongsideTheReadTools` | With `embabel.dice.mcp.writes-enabled=true` the client sees `dice_store` as well as the three read tools. |
| `WritableServerTests.aStoredFactComesBackFromRecallAndGet` | A fact stored in its own context with confidence 0.9 comes back from `dice_recall` and from `dice_get` by the id the store returned. |
| `LoopbackBindTests.isConfiguredToListenOnLoopbackOnly` | With the app's own settings `server.address` is set to a loopback address. |
| `LoopbackBindTests.theRunningServerListensOnLoopbackOnly` | The running Tomcat connector is bound to a loopback address, not every interface. |
| `ModularityTests.verifiesModuleStructure` | Spring Modulith finds no broken module rule in the app's packages. |
