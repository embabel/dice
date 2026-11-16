/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.dice;

import com.embabel.dice.proposition.extraction.ExtractionRunKey;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.kotlin.KotlinModule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the JSON shape of every public DICE type that exposes a {@code ContextId}.
 *
 * <p>Java sees a {@code ContextId} getter under a compiler-made name such as
 * {@code getContextId-SSYM5D4}. Without an annotation, a plain Jackson mapper writes the id under
 * that odd name, which nothing can read back. This test finds every such getter in the compiled
 * {@code dice} module and checks that the type writes its id as a plain string under the
 * property's own name, with and without the Kotlin module, and never writes the String twin.
 *
 * <p>It only scans the {@code dice} module. {@code DriftReport} and {@code DriftCheckResult} live
 * in {@code dice-metamodel} and have their own JSON tests there, so this test does not guard them.
 */
class ContextIdJsonContractJavaTest {

    private static final Pattern MANGLED_GETTER = Pattern.compile("get([A-Za-z]*ContextId)-.+");

    private static final String HOW_TO_FIX =
            "annotate the getter with @get:JsonProperty and add a sample to ContextIdFixtures.jsonSamples";

    /** Types that cannot be serialized whole, with the reason. Their annotations are still checked. */
    private static final Map<String, String> ANNOTATION_ONLY = Map.of(
            "com.embabel.dice.common.SourceAnalysisContext", "holds a resolver and a schema");

    /** Every public class with at least one mangled ContextId getter, with those getters. */
    private static final Map<Class<?>, List<Method>> GETTERS_BY_CLASS = new TreeMap<>(
            (a, b) -> a.getName().compareTo(b.getName()));

    @BeforeAll
    static void scanCompiledClasses() throws IOException, URISyntaxException, ClassNotFoundException {
        Path testClasses = Paths.get(ContextIdJsonContractJavaTest.class
                .getProtectionDomain().getCodeSource().getLocation().toURI());
        Path classes = testClasses.getParent().resolve("classes");
        Path packageRoot = classes.resolve("com/embabel/dice");
        assertTrue(Files.isDirectory(packageRoot), "no compiled dice classes at " + packageRoot);

        String separator = classes.getFileSystem().getSeparator();
        List<String> classNames;
        try (Stream<Path> files = Files.walk(packageRoot)) {
            classNames = files
                    .filter(file -> file.toString().endsWith(".class"))
                    .map(file -> classes.relativize(file).toString())
                    .map(name -> name.substring(0, name.length() - ".class".length())
                            .replace(separator, "."))
                    .filter(name -> !name.endsWith("package-info"))
                    .sorted()
                    .collect(Collectors.toList());
        }

        ClassLoader loader = ContextIdJsonContractJavaTest.class.getClassLoader();
        for (String name : classNames) {
            Class<?> type = Class.forName(name, false, loader);
            if (!Modifier.isPublic(type.getModifiers())) {
                continue;
            }
            List<Method> getters = new ArrayList<>();
            for (Method method : type.getMethods()) {
                if (method.getParameterCount() == 0
                        && !method.isSynthetic()
                        && !method.isBridge()
                        && MANGLED_GETTER.matcher(method.getName()).matches()) {
                    getters.add(method);
                }
            }
            if (!getters.isEmpty()) {
                getters.sort((a, b) -> a.getName().compareTo(b.getName()));
                GETTERS_BY_CLASS.put(type, getters);
            }
        }
    }

    private static ObjectMapper plainMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }

    private static ObjectMapper kotlinMapper() {
        return plainMapper().registerModule(new KotlinModule.Builder().build());
    }

    /** Turns "CommandedContextId" into "commandedContextId". */
    private static String propertyName(Method getter) {
        Matcher matcher = MANGLED_GETTER.matcher(getter.getName());
        assertTrue(matcher.matches());
        String captured = matcher.group(1);
        return Character.toLowerCase(captured.charAt(0)) + captured.substring(1);
    }

    private static String problem(Class<?> type, String what) {
        return type.getName() + ": " + what + "; " + HOW_TO_FIX;
    }

    @Test
    void scanFindsEveryKnownContextIdType() {
        assertTrue(GETTERS_BY_CLASS.size() >= 15,
                "expected at least 15 public classes with a ContextId getter, found "
                        + GETTERS_BY_CLASS.size() + ": " + GETTERS_BY_CLASS.keySet());
    }

    @Test
    void everyContextIdGetterIsAnnotatedAndHasASample() {
        Map<Class<?>, Object> samples = ContextIdFixtures.jsonSamples();
        List<String> problems = new ArrayList<>();

        for (Map.Entry<Class<?>, List<Method>> entry : GETTERS_BY_CLASS.entrySet()) {
            Class<?> type = entry.getKey();
            for (Method getter : entry.getValue()) {
                String expectedName = propertyName(getter);
                JsonProperty property = getter.getAnnotation(JsonProperty.class);
                if (property == null) {
                    problems.add(problem(type, getter.getName() + " has no @JsonProperty"));
                } else if (!expectedName.equals(property.value())) {
                    problems.add(problem(type, getter.getName() + " has @JsonProperty(\""
                            + property.value() + "\") but should say \"" + expectedName + "\""));
                }

                String twinName = "get" + Character.toUpperCase(expectedName.charAt(0))
                        + expectedName.substring(1) + "Value";
                Method twin = publicNoArgMethod(type, twinName);
                if (twin != null && twin.getAnnotation(JsonIgnore.class) == null) {
                    problems.add(problem(type, twinName + " has no @JsonIgnore"));
                }
            }
            if (!samples.containsKey(type) && !ANNOTATION_ONLY.containsKey(type.getName())) {
                problems.add(problem(type, "no sample in ContextIdFixtures.jsonSamples"));
            }
        }

        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void everySampleWritesItsContextIdAsAPlainStringWithPlainMapper() {
        assertSamplesFollowContract(plainMapper(), "plain mapper");
    }

    @Test
    void everySampleWritesItsContextIdAsAPlainStringWithKotlinModule() {
        assertSamplesFollowContract(kotlinMapper(), "Kotlin module mapper");
    }

    @Test
    void kotlinModuleReadsOldExtractionRunKeyJsonThatStillCarriesTheStringTwin() throws IOException {
        String oldShape = "{\"contextId\":\"acme\",\"contextIdValue\":\"acme\",\"runRef\":{\"runId\":\"run-1\"}}";

        ExtractionRunKey key = kotlinMapper().readValue(oldShape, ExtractionRunKey.class);

        assertEquals(ExtractionRunKey.of("acme", "run-1"), key);
        assertEquals("acme", key.getContextIdValue());
    }

    private static void assertSamplesFollowContract(ObjectMapper mapper, String mapperName) {
        Map<Class<?>, Object> samples = ContextIdFixtures.jsonSamples();
        List<String> problems = new ArrayList<>();

        for (Map.Entry<Class<?>, List<Method>> entry : GETTERS_BY_CLASS.entrySet()) {
            Class<?> type = entry.getKey();
            Object sample = samples.get(type);
            if (sample == null) {
                // A missing sample is reported by everyContextIdGetterIsAnnotatedAndHasASample.
                continue;
            }
            JsonNode tree = mapper.valueToTree(sample);
            for (Method getter : entry.getValue()) {
                String name = propertyName(getter);
                Object expected = invoke(getter, sample);
                JsonNode written = tree.get(name);
                if (expected == null) {
                    if (written == null || !written.isNull()) {
                        problems.add(problem(type, mapperName + " should write \"" + name
                                + "\" as JSON null, wrote " + written));
                    }
                } else if (written == null || !written.isTextual() || !expected.equals(written.asText())) {
                    problems.add(problem(type, mapperName + " should write \"" + name
                            + "\" as the string \"" + expected + "\", wrote " + written));
                }
            }
            collectBadFieldNames(type, mapperName, tree, problems);
        }

        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    /** Looks through every object in the tree for a compiler-made key or a String twin. */
    private static void collectBadFieldNames(Class<?> type, String mapperName, JsonNode node, List<String> problems) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey();
                if (key.contains("-")) {
                    problems.add(problem(type, mapperName + " wrote the compiler-made key \"" + key + "\""));
                }
                if (key.toLowerCase().endsWith("contextidvalue")) {
                    problems.add(problem(type, mapperName + " wrote the String twin \"" + key + "\""));
                }
                collectBadFieldNames(type, mapperName, field.getValue(), problems);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                collectBadFieldNames(type, mapperName, child, problems);
            }
        }
    }

    private static Method publicNoArgMethod(Class<?> type, String name) {
        try {
            return type.getMethod(name);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static Object invoke(Method getter, Object target) {
        try {
            return getter.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not call " + getter, e);
        }
    }
}
