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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that Java callers can reach every public function that takes a {@code ContextId}.
 *
 * <p>Kotlin gives such functions a mangled JVM name like {@code consolidate-abc1234}, which Java
 * cannot call. The rule in the user guide says each one needs a String form: a plain function
 * of the same name, or the same name with the suffix {@code Value}. This test reads the committed
 * API listings and looks for mangled functions that have neither, either in their own class or in
 * a supertype the class names. Anything left over must be listed in
 * {@code java-reachability-allowlist.txt} as a known gap, and the allowlist must shrink as gaps
 * are closed.
 */
class JavaReachabilityListingTest {

    private static final String FIX_MESSAGE =
            "Add a String form (see the ContextId rule in the user guide) or, for a known gap, "
                    + "a line in java-reachability-allowlist.txt";

    private static final Pattern MANGLED = Pattern.compile("fun ([A-Za-z0-9_]+)-[A-Za-z0-9_-]{7} \\(");
    private static final Pattern PLAIN = Pattern.compile("fun ([A-Za-z0-9_]+) \\(");
    private static final Pattern HEADER = Pattern.compile("^\\S.*\\bclass (\\S+)(?: : (.+?))? \\{$");

    /** These are known to have a String form, so they must never end up on the allowlist. */
    private static final List<String> KNOWN_REACHABLE = List.of(
            "DreamLoopOrchestrator#consolidate",
            "DreamLoopOrchestrator#consolidateNow",
            "MemoryMaintenanceOrchestrator#maintain",
            "CollectorRunner#run",
            "CollectorRunner#collect",
            "ConsolidationPass#run",
            "ChunkHistoryStore#clearByContext",
            "SessionConsolidationPass#run",
            "DecaySweepPass#run",
            "AbstractionPass#run",
            "ContradictionResolutionPass#run",
            "DefaultCollectorRunner#collect",
            "DefaultCollectorRunner#run",
            "DefaultDreamLoopOrchestrator#consolidate",
            "DefaultDreamLoopOrchestrator#consolidateNow",
            "DefaultDreamLoopOrchestrator#maintain",
            "DefaultMemoryMaintenanceOrchestrator#maintain");

    /** One class from the listing: its supertypes, its mangled names and its plain names. */
    private record ClassBlock(String name, List<String> supertypes, Set<String> mangled, Set<String> plain) {
        String simpleName() {
            return name.substring(name.lastIndexOf('/') + 1);
        }
    }

    private static Map<String, ClassBlock> blocks;
    private static Set<String> mangledMembers;
    private static Set<String> uncovered;
    private static Set<String> allowlist;

    @BeforeAll
    static void readListings() throws IOException {
        blocks = new LinkedHashMap<>();
        for (String file : List.of("api/dice.api", "api/dice.experimental.api")) {
            Path path = Paths.get(file);
            assertTrue(Files.exists(path), "Missing API listing " + path.toAbsolutePath());
            parse(Files.readAllLines(path, StandardCharsets.UTF_8));
        }
        assertTrue(blocks.size() > 100, "Expected the listings to hold many classes, found " + blocks.size());

        mangledMembers = new TreeSet<>();
        uncovered = new TreeSet<>();
        for (ClassBlock block : blocks.values()) {
            for (String name : block.mangled()) {
                String member = block.simpleName() + "#" + name;
                mangledMembers.add(member);
                if (!isCovered(block, name)) {
                    uncovered.add(member);
                }
            }
        }
        allowlist = readAllowlist();
    }

    @Test
    void everyMangledFunctionHasAStringFormOrIsAllowlisted() {
        Set<String> missing = new TreeSet<>(uncovered);
        missing.removeAll(allowlist);
        assertTrue(missing.isEmpty(), FIX_MESSAGE + ". Not reachable from Java: " + missing);
    }

    @Test
    void everyAllowlistLineIsStillAGap() {
        Set<String> stale = new TreeSet<>(allowlist);
        stale.removeAll(uncovered);
        assertTrue(stale.isEmpty(),
                "These allowlist lines are now covered or no longer exist; remove them from "
                        + "java-reachability-allowlist.txt: " + stale);
    }

    @Test
    void knownStringFormsCountAsCovered() {
        for (String member : KNOWN_REACHABLE) {
            assertTrue(mangledMembers.contains(member), member + " should be a mangled function in the listings");
            assertTrue(!uncovered.contains(member), member + " has a String form and should count as covered");
            assertTrue(!allowlist.contains(member), member + " must not be on the allowlist");
        }
    }

    @Test
    void stringFormOnASupertypeCoversAnImplementingClass() {
        ClassBlock impl = findBySimpleName("DefaultDreamLoopOrchestrator");
        assertNotNull(impl);
        assertTrue(impl.mangled().contains("consolidateNow"));
        assertTrue(!impl.plain().contains("consolidateNow"),
                "The implementing class has no plain consolidateNow of its own, so coverage comes from the interface");
        assertEquals(true, isCovered(impl, "consolidateNow"));
    }

    private static void parse(List<String> lines) {
        ClassBlock current = null;
        for (String line : lines) {
            Matcher header = HEADER.matcher(line);
            if (header.matches()) {
                List<String> supertypes = new ArrayList<>();
                if (header.group(2) != null) {
                    for (String s : header.group(2).split(",")) {
                        supertypes.add(s.trim());
                    }
                }
                current = new ClassBlock(header.group(1), supertypes, new TreeSet<>(), new HashSet<>());
                blocks.put(current.name(), current);
                continue;
            }
            if (line.equals("}")) {
                current = null;
                continue;
            }
            if (current == null) {
                continue;
            }
            Matcher plain = PLAIN.matcher(line);
            if (plain.find()) {
                current.plain().add(plain.group(1));
            }
            if (line.contains("$default")) {
                continue;
            }
            Matcher mangled = MANGLED.matcher(line);
            if (mangled.find()) {
                String name = mangled.group(1);
                if (!name.startsWith("get") && !name.startsWith("copy") && !name.startsWith("component")) {
                    current.mangled().add(name);
                }
            }
        }
    }

    /** True when this class or any supertype it names (followed through the listings) has a plain form. */
    private static boolean isCovered(ClassBlock start, String name) {
        Deque<ClassBlock> todo = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        todo.add(start);
        while (!todo.isEmpty()) {
            ClassBlock block = todo.poll();
            if (!seen.add(block.name())) {
                continue;
            }
            if (block.plain().contains(name) || block.plain().contains(name + "Value")) {
                return true;
            }
            for (String supertype : block.supertypes()) {
                ClassBlock parent = blocks.get(supertype);
                if (parent != null) {
                    todo.add(parent);
                }
            }
        }
        return false;
    }

    private static ClassBlock findBySimpleName(String simpleName) {
        return blocks.values().stream()
                .filter(b -> b.simpleName().equals(simpleName))
                .findFirst()
                .orElse(null);
    }

    private static Set<String> readAllowlist() throws IOException {
        Set<String> lines = new TreeSet<>();
        try (InputStream in = JavaReachabilityListingTest.class.getResourceAsStream("/java-reachability-allowlist.txt")) {
            assertNotNull(in, "java-reachability-allowlist.txt is missing from the test resources");
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                lines.add(trimmed);
            }
        }
        return lines;
    }
}
