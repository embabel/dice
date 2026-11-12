# Common

Test support shared by the examples. Other modules depend on it in test scope.

| Class | Purpose |
|---|---|
| `ScriptedChatModel` | A Spring AI `ChatModel` that replies from rules keyed on text in the prompt. A reply is a string or a Spring `Resource` holding a recorded one. A prompt that matches no rule throws. |
| `ScriptedLlm` | Wraps a `ScriptedChatModel` as an Embabel Agent `LlmService` named `scripted`. |

```java
@Bean
ScriptedChatModel scriptedChatModel() {
    return ScriptedChatModel.script()
            .whenPromptContains("Ada Okafor from Acme wrote in", new ClassPathResource("recorded/ticket-1042.json"))
            .build();
}

@Bean
LlmService<?> scriptedLlm(ScriptedChatModel scriptedChatModel) {
    return ScriptedLlm.service(scriptedChatModel);
}
```

Set `embabel.models.default-llm=scripted` so Embabel Agent uses it as the default model.
