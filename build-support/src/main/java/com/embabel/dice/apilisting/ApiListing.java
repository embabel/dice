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
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jetbrains.kotlin.abi.tools.AbiTools;
import org.jetbrains.kotlin.abi.tools.api.AbiFilters;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Keeps a module's public API listing in step with its compiled classes.
 *
 * <p>Each module has one listing, {@code api/<module>.api}, which holds the types that carry a
 * compatibility promise. A type marked {@code @ApiStatus.Experimental} makes no such promise, and the
 * annotation already says so, so the listing leaves it out along with its nested types and companion.
 * A file facade whose top-level functions are all experimental is left out too. A marker on a single
 * member or enum entry doesn't count: that member stays in the listing with its owner.
 *
 * <p>The build runs this in {@code check} mode right after compile, and it fails when the listing
 * differs from the compiled classes. {@code update} mode rewrites the listing.
 *
 * <p>Modules used to keep a second {@code <module>.experimental.api} listing. Check mode fails while
 * one is still around, so an old branch can't quietly keep it, and update mode deletes it.
 *
 * <p>A class compiled from a file under the {@code --generated} source root is left out, because
 * generated code carries no compatibility promise. The program stops when a hand-written
 * {@code --source} root has a file of the same name in the same package, because it cannot tell
 * which file a class came from.
 *
 * <p>Usage: {@code ApiListing <check|update> <classes-dir> <api-dir> <module-name>
 * [--generated=<source-root>] [--source=<source-root>]...}
 */
public final class ApiListing {

    private static final String EXPERIMENTAL = "org.jetbrains.annotations.ApiStatus.Experimental";

    private static final String GENERATED = "--generated=";

    private static final String SOURCE = "--source=";

    /** The first package declaration in a Kotlin or Java file. */
    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.`]+)", Pattern.MULTILINE);

    /**
     * How much older than the newest class from the same source file a class must be to count as left
     * from an earlier build. The compiler writes every class of a file in the same run, well inside
     * this, and a coarse file system clock still keeps them within it.
     */
    private static final Duration EARLIER_BUILD = Duration.ofSeconds(2);

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
        return run(args, out, err, ApiListing::dump);
    }

    /**
     * Checks or rewrites the listings, reading the classes with the given dump function.
     *
     * @param args the same arguments as {@link #main}
     * @param out where update mode reports the files it wrote
     * @param err where problems and listing differences go
     * @param dumper prints the ABI dump of some class files with some filters
     * @return 0 when the listings match or were written, 1 when they differ, 2 on bad input
     */
    static int run(String[] args, PrintStream out, PrintStream err, BiFunction<List<File>, AbiFilters, String> dumper)
            throws IOException {
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
        Set<String> handWritten = null;
        if (generated != null) {
            handWritten = sourceFiles(sourceRoots);
            List<File> kept = new ArrayList<>();
            Set<String> clashes = new TreeSet<>();
            for (File classFile : classFiles) {
                String source = sourcePath(classesDir, classFile.toPath(), sourceNames);
                if (source == null || !Files.isRegularFile(generated.resolve(source))) {
                    kept.add(classFile);
                } else if (handWritten.contains(source)) {
                    clashes.add(source);
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
        if (classFiles.isEmpty()) {
            err.println("No compiled classes to list under " + args[1] + ". Compile the module first.");
            return 2;
        }
        Path apiDir = Path.of(args[2]);
        String module = args[3];

        // Only reading a type name can fail with a message the user can act on. Anything the ABI
        // tools throw keeps its stack trace.
        List<String> full = blocks(dumper.apply(classFiles, new AbiFilters(Set.of(), Set.of(), Set.of(), Set.of())));
        List<String> withoutExperimental = blocks(
                dumper.apply(classFiles, new AbiFilters(Set.of(), Set.of(), Set.of(), Set.of(EXPERIMENTAL))));
        List<String> stableBlocks = new ArrayList<>();
        try {
            // A type is experimental when the marker sits on the type itself: leaving marked declarations
            // out drops the whole type. A marker on an enum entry or a member only drops that member, so
            // its owner stays stable. A nested type or companion follows its experimental owner.
            Set<String> experimentalTypes = new HashSet<>();
            for (String block : full) {
                experimentalTypes.add(typeName(block));
            }
            for (String block : withoutExperimental) {
                experimentalTypes.remove(typeName(block));
            }
            for (String block : full) {
                String name = typeName(block);
                boolean isExperimental = experimentalTypes.contains(name)
                        || experimentalTypes.stream().anyMatch(owner -> name.startsWith(owner + "$"));
                if (!isExperimental) {
                    stableBlocks.add(block);
                }
            }
        } catch (IllegalStateException e) {
            err.println(e.getMessage());
            return 2;
        }
        String stable = listing(stableBlocks);

        boolean matches = sync(apiDir.resolve(module + ".api"), stable, update, out, err);
        boolean leftoverGone =
                removeOldExperimentalListing(apiDir.resolve(module + ".experimental.api"), update, out, err);
        if (!matches) {
            err.println("The public API changed. Review the difference above, then regenerate the"
                    + " listings with: ./mvnw -B -Dapi.listing.mode=update process-classes");
            // Worked out only after a failure, so a passing build skips the extra reading
            Set<String> sources = sourceRoots.isEmpty() ? null : handWritten != null ? handWritten : sourceFiles(sourceRoots);
            Set<String> stale = leftFromEarlierBuild(classesDir, classFiles, sources, sourceNames);
            if (!stale.isEmpty()) {
                err.println("These classes look left from an earlier build, because their source file is gone"
                        + " or no longer compiles to them, and ./mvnw clean removes them: " + String.join(", ", stale));
            }
            return 1;
        }
        return leftoverGone ? 0 : 1;
    }

    /**
     * Deals with an experimental listing left from before experimental types were dropped from the
     * listing. Update mode deletes it. Check mode fails and says to delete it.
     *
     * @param file where the old experimental listing would be
     * @param update whether to delete the file rather than report it
     * @param out where update mode reports the deletion
     * @param err where check mode reports the file
     * @return true when no such file is left
     */
    private static boolean removeOldExperimentalListing(Path file, boolean update, PrintStream out, PrintStream err)
            throws IOException {
        if (!Files.exists(file)) {
            return true;
        }
        if (update) {
            Files.delete(file);
            out.println("Deleted " + file);
            return true;
        }
        err.println(file + " is no longer used, because experimental types are left out of the API listing."
                + " Delete it.");
        return false;
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
     * Collects the source files under some source roots, each as its package directory and file name,
     * such as {@code com/embabel/dice/Mention.kt}.
     *
     * <p>Kotlin does not make a file's directory match its package, so a Kotlin or Java file is
     * recorded under both its directory and the package it declares.
     *
     * @param roots the source roots, any of which may be missing
     * @return the package directory and file name of each file
     */
    private static Set<String> sourceFiles(List<Path> roots) throws IOException {
        Set<String> files = new HashSet<>();
        for (Path root : roots) {
            if (Files.isDirectory(root)) {
                List<Path> paths;
                try (Stream<Path> walk = Files.walk(root)) {
                    paths = walk.filter(Files::isRegularFile).toList();
                }
                for (Path path : paths) {
                    String name = path.getFileName().toString();
                    Path dir = root.relativize(path).getParent();
                    files.add(packagePath(dir == null ? "" : dir.toString().replace(File.separatorChar, '/'), name));
                    if (name.endsWith(".kt") || name.endsWith(".java")) {
                        // Read as Latin-1 so an odd byte cannot stop the read; package names are plain ASCII
                        Matcher declared = PACKAGE.matcher(new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1));
                        String pkg = declared.find() ? declared.group(1).replace("`", "").replace('.', '/') : "";
                        files.add(packagePath(pkg, name));
                    }
                }
            }
        }
        return files;
    }

    private static String packagePath(String packageDir, String fileName) {
        return packageDir.isEmpty() ? fileName : packageDir + "/" + fileName;
    }

    /**
     * Finds the source file a class was compiled from, as its package directory and file name.
     *
     * @param classesDir the module's class directory
     * @param classFile a class file under it
     * @param sourceNames source file names already read, by outermost class file
     * @return the path, such as {@code com/embabel/dice/Mention.kt}, or null when the class records no
     *     source file name
     */
    private static String sourcePath(Path classesDir, Path classFile, Map<Path, String> sourceNames)
            throws IOException {
        String source = sourceName(classFile, sourceNames);
        if (source.isEmpty()) {
            return null;
        }
        Path packageDir = classesDir.relativize(classFile).getParent();
        return packagePath(packageDir == null ? "" : packageDir.toString().replace(File.separatorChar, '/'), source);
    }

    /**
     * Picks out the classes that look left from an earlier build, for the hint after a failed check.
     *
     * <p>Two signs give such a class away. Its source file is in no hand-written source root, because
     * the file was deleted or moved. Or it is older than the newest class from the same source file:
     * the compiler writes every class of a file it compiles, so a class it no longer writes keeps the
     * time of an earlier build. That second sign catches a class deleted from a file that still exists.
     *
     * @param classesDir the module's class directory
     * @param classFiles the classes that went into the listing
     * @param handWritten the hand-written source files from {@link #sourceFiles}, or null when the
     *     program was given no source roots
     * @param sourceNames source file names already read, by outermost class file
     * @return the class names, such as {@code com/embabel/dice/Mention}, leaving out a nested class
     *     whose outer class is named
     */
    private static Set<String> leftFromEarlierBuild(Path classesDir, List<File> classFiles, Set<String> handWritten,
            Map<Path, String> sourceNames) throws IOException {
        Map<String, List<Path>> bySource = new HashMap<>();
        Map<Path, FileTime> times = new HashMap<>();
        Map<String, FileTime> newest = new HashMap<>();
        for (File file : classFiles) {
            Path classFile = file.toPath();
            String source = sourcePath(classesDir, classFile, sourceNames);
            if (source != null) {
                FileTime time = Files.getLastModifiedTime(classFile);
                times.put(classFile, time);
                bySource.computeIfAbsent(source, key -> new ArrayList<>()).add(classFile);
                newest.merge(source, time, (a, b) -> a.compareTo(b) >= 0 ? a : b);
            }
        }
        Set<String> stale = new TreeSet<>();
        for (Map.Entry<String, List<Path>> entry : bySource.entrySet()) {
            boolean gone = handWritten != null && !handWritten.contains(entry.getKey());
            FileTime cutoff = FileTime.from(newest.get(entry.getKey()).toInstant().minus(EARLIER_BUILD));
            for (Path classFile : entry.getValue()) {
                if (gone || times.get(classFile).compareTo(cutoff) < 0) {
                    String relative = classesDir.relativize(classFile).toString().replace(File.separatorChar, '/');
                    stale.add(relative.substring(0, relative.length() - ".class".length()));
                }
            }
        }
        stale.removeIf(name -> name.contains("$") && stale.contains(name.substring(0, name.indexOf('$'))));
        return stale;
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
     * Brings listing text to the form the program writes: LF line endings and one newline at the end.
     *
     * @param text the listing text as read
     * @return the normalised text, or an empty string when the text is blank
     */
    private static String normalised(String text) {
        return text.isBlank() ? "" : text.replace("\r\n", "\n").stripTrailing() + "\n";
    }

    /**
     * Writes the listing in update mode. Otherwise reports whether the committed file matches.
     *
     * <p>Both modes ignore line endings and whitespace at the end of the file, so a Windows checkout or
     * an editor that trims the last lines neither fails the check nor makes update rewrite the file.
     */
    private static boolean sync(Path file, String actual, boolean update, PrintStream out, PrintStream err)
            throws IOException {
        String committed = normalised(Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "");
        if (update) {
            if (!committed.equals(actual)) {
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
