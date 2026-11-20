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
import java.nio.file.DirectoryStream;
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
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that Java callers can reach every public function that takes a {@code ContextId}.
 *
 * <p>Kotlin gives such functions a mangled JVM name like {@code consolidate-abc1234}, which Java
 * cannot call. The rule in the user guide says each one needs a String form: a plain function
 * of the same name, or the same name with the suffix {@code Value}. This test reads every
 * committed API listing in the repository and looks for mangled functions that have no such
 * form, either in their own class or in a supertype the class names. A plain function only
 * counts when it takes exactly the same JVM parameters as the mangled one, so an unrelated
 * overload that happens to share the name does not hide a gap. Anything left over must be listed
 * in {@code java-reachability-allowlist.txt} as a known gap, and the allowlist must shrink as
 * gaps are closed.
 */
class JavaReachabilityListingTest {

    private static final String FIX_MESSAGE =
            "Add a String form (see the ContextId rule in the user guide) or, for a known gap, "
                    + "a line in java-reachability-allowlist.txt";

    /** A function line: its name, the mangling hash if there is one, and its parameter descriptor. */
    private static final Pattern FUN = Pattern.compile("fun ([A-Za-z0-9_]+)(-[A-Za-z0-9_-]{7})? (\\([^)]*\\))");
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

    /**
     * One class from a listing: the file it came from, its supertypes, and for each function
     * name the parameter descriptors of its mangled and its plain overloads.
     */
    private record ClassBlock(
            String name,
            String source,
            List<String> supertypes,
            Map<String, Set<String>> mangled,
            Map<String, Set<String>> plain) {

        String simpleName() {
            return name.substring(name.lastIndexOf('/') + 1);
        }

        /** The allowlist line for one mangled overload, like {@code com/x/Foo#bar(Ljava/lang/String;)}. */
        String member(String function, String params) {
            return name + "#" + function + params;
        }
    }

    private static Map<String, ClassBlock> blocks;
    private static Set<String> uncovered;
    private static Set<String> allowlist;

    @BeforeAll
    static void readListings() throws IOException {
        List<Path> listings = findListings();
        blocks = new LinkedHashMap<>();
        for (Path path : listings) {
            parse(path.getParent().getParent().getFileName() + "/api/" + path.getFileName(),
                    Files.readAllLines(path, StandardCharsets.UTF_8), blocks);
        }
        assertTrue(blocks.size() > 100, "Expected the listings to hold many classes, found " + blocks.size());

        uncovered = uncoveredMembers(blocks);
        allowlist = readAllowlist();
        printCounts(listings);
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
        for (String known : KNOWN_REACHABLE) {
            String simpleName = known.substring(0, known.indexOf('#'));
            String function = known.substring(known.indexOf('#') + 1);
            List<String> members = new ArrayList<>();
            for (ClassBlock block : blocks.values()) {
                if (block.simpleName().equals(simpleName) && block.mangled().containsKey(function)) {
                    for (String params : block.mangled().get(function)) {
                        members.add(block.member(function, params));
                    }
                }
            }
            assertFalse(members.isEmpty(), known + " should be a mangled function in the listings");
            for (String member : members) {
                assertFalse(uncovered.contains(member), member + " has a String form and should count as covered");
                assertFalse(allowlist.contains(member), member + " must not be on the allowlist");
            }
        }
    }

    @Test
    void stringFormOnASupertypeCoversAnImplementingClass() {
        ClassBlock impl = findBySimpleName("DefaultDreamLoopOrchestrator");
        assertNotNull(impl);
        assertTrue(impl.mangled().containsKey("consolidateNow"));
        assertFalse(impl.plain().containsKey("consolidateNow"),
                "The implementing class has no plain consolidateNow of its own, so coverage comes from the interface");
        for (String params : impl.mangled().get("consolidateNow")) {
            assertTrue(isCovered(blocks, impl, "consolidateNow", params));
        }
    }

    @Test
    void graphQueryContextOverloadsAreGapsEvenThoughPlainOverloadsShareTheirNames() {
        String graph = "com/embabel/dice/proposition/GraphQueryCapable";
        assertTrue(blocks.get(graph).plain().containsKey("neighborhood"));
        for (String member : List.of(
                graph + "#neighborhood(Ljava/lang/String;ILjava/lang/String;)",
                graph + "#neighborhood(Ljava/lang/String;ILjava/lang/String;I)",
                graph + "#pathBetween(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)",
                graph + "#pathBetween(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)",
                graph + "#whyExplain(Ljava/lang/String;Ljava/lang/String;)")) {
            assertTrue(uncovered.contains(member), member + " has no Java form and should count as a gap");
        }
    }

    @Test
    void aSameNamePlainOverloadWithOtherParametersDoesNotCoverAMangledFunction() {
        List<String> listing = List.of(
                "public abstract interface class com/example/Store {",
                "\tpublic abstract fun find (Ljava/lang/String;)Ljava/util/List;",
                "\tpublic abstract fun find-abc1234 (Ljava/lang/String;Ljava/lang/String;)Ljava/util/List;",
                "\tpublic abstract fun load (Ljava/lang/String;)Ljava/lang/Object;",
                "\tpublic abstract fun load-abc1234 (Ljava/lang/String;)Ljava/util/List;",
                "\tpublic abstract fun runValue (Ljava/lang/String;I)V",
                "\tpublic abstract fun run-abc1234 (Ljava/lang/String;I)V",
                "}",
                "",
                "public final class com/example/DefaultStore : com/example/Store {",
                "\tpublic fun load-abc1234 (Ljava/lang/String;)Ljava/util/List;",
                "\tpublic fun find-abc1234 (Ljava/lang/String;Ljava/lang/String;)Ljava/util/List;",
                "}",
                "");
        Map<String, ClassBlock> synthetic = new LinkedHashMap<>();
        parse("synthetic.api", listing, synthetic);

        assertEquals(
                Set.of("com/example/Store#find(Ljava/lang/String;Ljava/lang/String;)",
                        "com/example/DefaultStore#find(Ljava/lang/String;Ljava/lang/String;)"),
                uncoveredMembers(synthetic),
                "find has only a one-argument plain overload, so its two-argument mangled form is a gap; "
                        + "load and run have plain forms with the same parameters, even when the return type differs");
    }

    /**
     * Finds every committed listing in the repository. The test runs inside the dice module, so
     * the repository root is its parent, and each module keeps its listings in {@code api/}.
     */
    private static List<Path> findListings() throws IOException {
        Path root = Paths.get("").toAbsolutePath().getParent();
        assertTrue(Files.exists(root.resolve("dice/api/dice.api")),
                "Expected to run inside the dice module, but found no dice/api/dice.api under " + root);
        List<Path> listings = new ArrayList<>();
        try (DirectoryStream<Path> modules = Files.newDirectoryStream(root, Files::isDirectory)) {
            for (Path module : modules) {
                Path api = module.resolve("api");
                if (!Files.isDirectory(api)) {
                    continue;
                }
                try (DirectoryStream<Path> files = Files.newDirectoryStream(api, "*.api")) {
                    files.forEach(listings::add);
                }
            }
        }
        listings.sort(null);
        for (String expected : List.of("dice/api/dice.api", "dice-storage/api/dice-storage.api",
                "dice-metamodel/api/dice-metamodel.api")) {
            assertTrue(listings.contains(root.resolve(expected)), "Missing API listing " + root.resolve(expected));
        }
        return listings;
    }

    /** Reads one listing into {@code into}, keyed by full class name, adding to a class already seen. */
    private static void parse(String source, List<String> lines, Map<String, ClassBlock> into) {
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
                ClassBlock fresh = new ClassBlock(header.group(1), source, supertypes, new TreeMap<>(), new TreeMap<>());
                current = into.computeIfAbsent(fresh.name(), n -> fresh);
                if (current != fresh) {
                    current.supertypes().addAll(supertypes);
                }
                continue;
            }
            if (line.equals("}")) {
                current = null;
                continue;
            }
            if (current == null || line.contains("$default")) {
                continue;
            }
            Matcher fun = FUN.matcher(line);
            if (!fun.find()) {
                continue;
            }
            String name = fun.group(1);
            String params = fun.group(3);
            if (fun.group(2) == null) {
                current.plain().computeIfAbsent(name, n -> new TreeSet<>()).add(params);
            } else if (!name.startsWith("get") && !name.startsWith("copy") && !name.startsWith("component")) {
                current.mangled().computeIfAbsent(name, n -> new TreeSet<>()).add(params);
            }
        }
    }

    /** Every mangled overload in {@code listing} that Java has no way to call. */
    private static Set<String> uncoveredMembers(Map<String, ClassBlock> listing) {
        Set<String> result = new TreeSet<>();
        for (ClassBlock block : listing.values()) {
            block.mangled().forEach((name, overloads) -> {
                for (String params : overloads) {
                    if (!isCovered(listing, block, name, params)) {
                        result.add(block.member(name, params));
                    }
                }
            });
        }
        return result;
    }

    /**
     * True when this class or any supertype it names (followed through the listings) has a plain
     * function called {@code name} or {@code name + "Value"} that takes exactly {@code params}.
     */
    private static boolean isCovered(Map<String, ClassBlock> listing, ClassBlock start, String name, String params) {
        Deque<ClassBlock> todo = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        todo.add(start);
        while (!todo.isEmpty()) {
            ClassBlock block = todo.poll();
            if (!seen.add(block.name())) {
                continue;
            }
            for (String candidate : List.of(name, name + "Value")) {
                if (block.plain().getOrDefault(candidate, Set.of()).contains(params)) {
                    return true;
                }
            }
            for (String supertype : block.supertypes()) {
                ClassBlock parent = listing.get(supertype);
                if (parent != null) {
                    todo.add(parent);
                }
            }
        }
        return false;
    }

    /** Prints how many mangled overloads each listing has and how many of them Java can reach. */
    private static void printCounts(List<Path> listings) {
        Map<String, int[]> counts = new TreeMap<>();
        for (Path path : listings) {
            counts.put(path.getParent().getParent().getFileName() + "/api/" + path.getFileName(), new int[2]);
        }
        for (ClassBlock block : blocks.values()) {
            block.mangled().forEach((name, overloads) -> {
                for (String params : overloads) {
                    int[] count = counts.get(block.source());
                    count[uncovered.contains(block.member(name, params)) ? 1 : 0]++;
                }
            });
        }
        counts.forEach((file, count) -> System.out.println(
                "Java reachability " + file + ": covered " + count[0] + ", uncovered " + count[1]));
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
