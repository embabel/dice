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
package com.embabel.dice.apilisting;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.jetbrains.kotlin.abi.tools.AbiTools;
import org.jetbrains.kotlin.abi.tools.api.AbiFilters;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Keeps a module's public API listing in step with its compiled classes.
 *
 * <p>Each module has two listings under its {@code api} directory. {@code <module>.api} holds the
 * types that carry a compatibility promise. {@code <module>.experimental.api} holds the types
 * marked {@code @ApiStatus.Experimental}, and is absent when a module has none.
 *
 * <p>The build runs this in {@code check} mode right after compile, and it fails when a listing
 * differs from the compiled classes. {@code update} mode rewrites the listings.
 *
 * <p>A class compiled from a file under the {@code --generated} source root is left out, because
 * generated code carries no compatibility promise. The program stops when such a file name also
 * appears under a hand-written {@code --source} root, because it cannot tell which file a class
 * came from.
 *
 * <p>Usage: {@code ApiListing <check|update> <classes-dir> <api-dir> <module-name>
 * [--generated=<source-root>] [--source=<source-root>]...}
 */
public final class ApiListing {

    private static final String EXPERIMENTAL = "org.jetbrains.annotations.ApiStatus.Experimental";

    private static final String GENERATED = "--generated=";

    private static final String SOURCE = "--source=";

    public static void main(String[] args) throws IOException {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * Checks or rewrites the listings and returns the exit status.
     *
     * @param args the same arguments as {@link #main}
     * @param out where update mode reports the files it wrote
     * @param err where problems and listing differences go
     * @return 0 when the listings match or were written, 1 when they differ, 2 on bad input
     */
    static int run(String[] args, PrintStream out, PrintStream err) throws IOException {
        List<String> positional = new ArrayList<>();
        Path generated = null;
        List<Path> sourceRoots = new ArrayList<>();
        for (String arg : args) {
            if (arg.startsWith(SOURCE)) {
                sourceRoots.add(Path.of(arg.substring(SOURCE.length())));
            } else if (arg.startsWith(GENERATED)) {
                // An empty value means the module has no generated sources
                String root = arg.substring(GENERATED.length());
                generated = root.isBlank() ? null : Path.of(root);
            } else {
                positional.add(arg);
            }
        }
        args = positional.toArray(String[]::new);
        if (args.length != 4 || !(args[0].equals("check") || args[0].equals("update"))) {
            err.println("Usage: ApiListing <check|update> <classes-dir> <api-dir> <module-name>"
                    + " [--generated=<source-root>] [--source=<source-root>]...");
            return 2;
        }
        boolean update = args[0].equals("update");
        Path classesDir = Path.of(args[1]);
        List<File> classFiles = classFiles(classesDir);
        Map<Path, String> sourceNames = new HashMap<>();
        Set<String> handWritten = fileNames(sourceRoots);
        if (generated != null) {
            List<File> kept = new ArrayList<>();
            Set<String> clashes = new TreeSet<>();
            for (File classFile : classFiles) {
                if (!isGenerated(classesDir, classFile.toPath(), generated, sourceNames)) {
                    kept.add(classFile);
                } else if (handWritten.contains(sourceName(classFile.toPath(), sourceNames))) {
                    clashes.add(sourceName(classFile.toPath(), sourceNames));
                }
            }
            if (!clashes.isEmpty()) {
                for (String clash : clashes) {
                    err.println(clash + " is both generated and hand-written, so the listing cannot tell"
                            + " their classes apart. Rename the hand-written file.");
                }
                return 2;
            }
            classFiles = kept;
        }
        Set<String> stale = new TreeSet<>();
        if (!sourceRoots.isEmpty()) {
            for (File classFile : classFiles) {
                String source = sourceName(classFile.toPath(), sourceNames);
                String relative = classesDir.relativize(classFile.toPath()).toString().replace(File.separatorChar, '/');
                if (!source.isEmpty() && !handWritten.contains(source) && !relative.contains("$")) {
                    stale.add(relative.substring(0, relative.length() - ".class".length()));
                }
            }
        }
        if (classFiles.isEmpty()) {
            err.println("No compiled classes to list under " + args[1] + ". Compile the module first.");
            return 2;
        }
        Path apiDir = Path.of(args[2]);
        String module = args[3];

        // A type is experimental when the marker sits on the type itself: leaving marked declarations
        // out drops the whole type. A marker on an enum entry or a member only drops that member, so
        // its owner stays stable. A nested type or companion follows its experimental owner.
        Set<String> all = new HashSet<>();
        List<String> full = blocks(dump(classFiles, new AbiFilters(Set.of(), Set.of(), Set.of(), Set.of())));
        for (String block : full) {
            all.add(typeName(block));
        }
        Set<String> experimentalTypes = new HashSet<>(all);
        for (String block : blocks(dump(classFiles, new AbiFilters(Set.of(), Set.of(), Set.of(), Set.of(EXPERIMENTAL))))) {
            experimentalTypes.remove(typeName(block));
        }
        List<String> stableBlocks = new ArrayList<>();
        List<String> experimentalBlocks = new ArrayList<>();
        for (String block : full) {
            String name = typeName(block);
            boolean isExperimental = experimentalTypes.contains(name)
                    || experimentalTypes.stream().anyMatch(owner -> name.startsWith(owner + "$"));
            (isExperimental ? experimentalBlocks : stableBlocks).add(block);
        }
        String stable = listing(stableBlocks);
        String experimental = listing(experimentalBlocks);

        boolean matches = sync(apiDir.resolve(module + ".api"), stable, update, out, err);
        matches &= sync(apiDir.resolve(module + ".experimental.api"), experimental, update, out, err);
        if (!matches) {
            err.println("The public API changed. Review the difference above, then regenerate the"
                    + " listings with: ./mvnw -B -Dapi.listing.mode=update process-classes");
            if (!stale.isEmpty()) {
                err.println("These classes have no source file, so they come from an earlier build and"
                        + " ./mvnw clean removes them: " + String.join(", ", stale));
            }
            return 1;
        }
        return 0;
    }

    private static List<File> classFiles(Path classesDir) throws IOException {
        List<File> files = new ArrayList<>();
        if (Files.isDirectory(classesDir)) {
            try (Stream<Path> walk = Files.walk(classesDir)) {
                walk.filter(path -> path.toString().endsWith(".class")).sorted().forEach(path -> files.add(path.toFile()));
            }
        }
        return files;
    }

    /**
     * Collects the names of the files under some source roots.
     *
     * @param roots the source roots, any of which may be missing
     * @return the file names, such as {@code Mention.kt}
     */
    private static Set<String> fileNames(List<Path> roots) throws IOException {
        Set<String> names = new HashSet<>();
        for (Path root : roots) {
            if (Files.isDirectory(root)) {
                try (Stream<Path> walk = Files.walk(root)) {
                    walk.filter(Files::isRegularFile).forEach(path -> names.add(path.getFileName().toString()));
                }
            }
        }
        return names;
    }

    /**
     * Tells whether a class was compiled from a file under the generated source root. A class with no
     * source file name is kept.
     *
     * @param classesDir the module's class directory
     * @param classFile a class file under it
     * @param generatedRoot the generated source root
     * @param sourceNames source file names already read, by outermost class file
     * @return true when the source file exists under the root, in the class's package directory
     */
    private static boolean isGenerated(Path classesDir, Path classFile, Path generatedRoot,
            Map<Path, String> sourceNames) throws IOException {
        String source = sourceName(classFile, sourceNames);
        if (source.isEmpty()) {
            return false;
        }
        Path packageDir = classesDir.relativize(classFile).getParent();
        Path sourceDir = packageDir == null ? generatedRoot : generatedRoot.resolve(packageDir.toString());
        return Files.isRegularFile(sourceDir.resolve(source));
    }

    /**
     * Finds the source file name for a class, read once per outermost class.
     *
     * <p>The name comes from the outermost class, because a class the compiler copies from an inline
     * function names the inline function's file.
     *
     * @param classFile the class file
     * @param sourceNames source file names already read, by outermost class file
     * @return the name, or an empty string when the class records none
     */
    private static String sourceName(Path classFile, Map<Path, String> sourceNames) throws IOException {
        String fileName = classFile.getFileName().toString();
        int nested = fileName.indexOf('$');
        Path outer = nested > 0 ? classFile.resolveSibling(fileName.substring(0, nested) + ".class") : classFile;
        Path read = Files.exists(outer) ? outer : classFile;
        String cached = sourceNames.get(read);
        if (cached == null) {
            String source = sourceFile(read);
            cached = source == null ? "" : source;
            sourceNames.put(read, cached);
        }
        return cached;
    }

    /**
     * Reads the source file name a class records, such as {@code Mention.kt}.
     *
     * @param classFile the class file
     * @return the name, or null when the class records none
     */
    private static String sourceFile(Path classFile) throws IOException {
        String[] source = new String[1];
        new ClassReader(Files.readAllBytes(classFile)).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visitSource(String file, String debug) {
                source[0] = file;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
        return source[0];
    }

    /** Splits a dump into one block per type. */
    private static List<String> blocks(String dump) {
        List<String> blocks = new ArrayList<>();
        for (String block : dump.split("\n\n")) {
            if (!block.isBlank()) {
                blocks.add(block.strip());
            }
        }
        return blocks;
    }

    /** Reads the type name from a block's first line, such as {@code public final class a/b/C : a/b/D \{}. */
    static String typeName(String block) {
        String header = block.substring(0, block.indexOf('\n') < 0 ? block.length() : block.indexOf('\n'));
        int keyword = header.indexOf(" class ");
        if (keyword < 0) {
            throw new IllegalStateException("Cannot read a type name from the listing line: " + header);
        }
        String afterKeyword = header.substring(keyword + " class ".length());
        return afterKeyword.split(" ")[0];
    }

    private static String dump(List<File> classFiles, AbiFilters filters) {
        StringBuilder listing = new StringBuilder();
        AbiTools.INSTANCE.getV2().printJvmDump(listing, classFiles, filters);
        return listing.toString();
    }

    /**
     * Joins type blocks into listing text: one blank line between blocks and one newline at the end.
     *
     * @param blocks the type blocks in listing order
     * @return the listing text, or an empty string when there are no blocks
     */
    private static String listing(List<String> blocks) {
        return blocks.isEmpty() ? "" : String.join("\n\n", blocks) + "\n";
    }

    /**
     * Writes the listing in update mode. Otherwise reports whether the committed file matches.
     *
     * <p>Line endings and whitespace at the end of the file are not compared, so a Windows checkout or
     * an editor that trims the last lines does not fail the check.
     */
    private static boolean sync(Path file, String actual, boolean update, PrintStream out, PrintStream err)
            throws IOException {
        String raw = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        if (update) {
            if (!raw.equals(actual)) {
                if (actual.isEmpty()) {
                    Files.deleteIfExists(file);
                } else {
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, actual, StandardCharsets.UTF_8);
                }
                out.println("Updated " + file);
            }
            return true;
        }
        String committed = raw.isBlank() ? "" : raw.replace("\r\n", "\n").stripTrailing() + "\n";
        if (committed.equals(actual)) {
            return true;
        }
        Path expected = Files.createTempFile("api-listing-committed", ".api");
        Path generated = Files.createTempFile("api-listing", ".api");
        try {
            Files.writeString(expected, committed, StandardCharsets.UTF_8);
            Files.writeString(generated, actual, StandardCharsets.UTF_8);
            err.println(file + " does not match the compiled classes:");
            err.println(AbiTools.INSTANCE.filesDiff(expected.toFile(), generated.toFile()));
        } finally {
            Files.delete(expected);
            Files.delete(generated);
        }
        return false;
    }
}
