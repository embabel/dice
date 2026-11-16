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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * generated code carries no compatibility promise.
 *
 * <p>Usage: {@code ApiListing <check|update> <classes-dir> <api-dir> <module-name> [--generated=<source-root>]}
 */
public final class ApiListing {

    private static final String EXPERIMENTAL = "org.jetbrains.annotations.ApiStatus.Experimental";

    private static final String GENERATED = "--generated=";

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
        for (String arg : args) {
            if (arg.startsWith(GENERATED)) {
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
                    + " [--generated=<source-root>]");
            return 2;
        }
        boolean update = args[0].equals("update");
        Path classesDir = Path.of(args[1]);
        List<File> classFiles = classFiles(classesDir);
        if (generated != null) {
            for (File classFile : List.copyOf(classFiles)) {
                if (isGenerated(classesDir, classFile.toPath(), generated)) {
                    classFiles.remove(classFile);
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
            err.println("Types you did not add come from an earlier build, and ./mvnw clean removes them.");
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
     * Tells whether a class was compiled from a file under the generated source root.
     *
     * <p>The source file name comes from the outermost class, because a class the compiler copies
     * from an inline function names the inline function's file. A class with no source file name is
     * kept.
     *
     * @param classesDir the module's class directory
     * @param classFile a class file under it
     * @param generatedRoot the generated source root
     * @return true when the source file exists under the root, in the class's package directory
     */
    private static boolean isGenerated(Path classesDir, Path classFile, Path generatedRoot) throws IOException {
        String fileName = classFile.getFileName().toString();
        int nested = fileName.indexOf('$');
        Path outer = nested > 0 ? classFile.resolveSibling(fileName.substring(0, nested) + ".class") : classFile;
        String source = sourceFile(Files.exists(outer) ? outer : classFile);
        if (source == null) {
            return false;
        }
        Path packageDir = classesDir.relativize(classFile).getParent();
        Path sourceDir = packageDir == null ? generatedRoot : generatedRoot.resolve(packageDir.toString());
        return Files.isRegularFile(sourceDir.resolve(source));
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
