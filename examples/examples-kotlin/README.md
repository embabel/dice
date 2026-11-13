# Kotlin

The same proposition API as the Java example, written the way Kotlin allows.
`PropositionsInKotlinTests` stores four facts by hand and reads them back.

```bash
./mvnw -B -f examples/pom.xml -pl examples-kotlin -am verify
```

| Kotlin | Java |
|---|---|
| `PropositionQuery forContextId acme`, with a typed `ContextId` | `PropositionQuery.againstContext("acme")` |
| `Proposition(acme, "...", 0.95) { decay(0.005) }`, a block that sets the other values | `Proposition.draft("acme", "...", 0.95).withDecay(0.005).toProposition()` |

The [Java example](../examples-java) shows extraction, entity resolution and persistence in Java.
