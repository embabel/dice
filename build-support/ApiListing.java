import java.io.File;
import java.io.IOException;
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

/**
 * Keeps a module's public API listing in step with its compiled classes.
 *
 * <p>Each module has two listings under its {@code api} directory. {@code <module>.api} holds the
 * types that carry a compatibility promise. {@code <module>.experimental.api} holds the types
 * marked {@code @ApiStatus.Experimental}, and is absent when a module has none.
 *
 * <p>The build runs this in {@code check} mode during {@code verify}, and it fails when a listing
 * differs from the compiled classes. {@code update} mode rewrites the listings.
 *
 * <p>Usage: {@code ApiListing <check|update> <classes-dir> <api-dir> <module-name>}
 */
public final class ApiListing {

    private static final String EXPERIMENTAL = "org.jetbrains.annotations.ApiStatus.Experimental";

    public static void main(String[] args) throws IOException {
        if (args.length != 4 || !(args[0].equals("check") || args[0].equals("update"))) {
            System.err.println("Usage: ApiListing <check|update> <classes-dir> <api-dir> <module-name>");
            System.exit(2);
        }
        boolean update = args[0].equals("update");
        List<File> classFiles = classFiles(Path.of(args[1]));
        if (classFiles.isEmpty()) {
            System.err.println("No compiled classes under " + args[1] + ". Compile the module first.");
            System.exit(2);
        }
        Path apiDir = Path.of(args[2]);
        String module = args[3];

        // A type is experimental when the marker sits on the type itself: leaving marked declarations
        // out drops the whole type. A marker on an enum entry or a member only drops that member, so
        // its owner stays stable. A nested type or companion follows its experimental owner.
        Set<String> all = new HashSet<>();
        String full = dump(classFiles, new AbiFilters(Set.of(), Set.of(), Set.of(), Set.of()));
        for (String block : blocks(full)) {
            all.add(typeName(block));
        }
        Set<String> experimentalTypes = new HashSet<>(all);
        for (String block : blocks(dump(classFiles, new AbiFilters(Set.of(), Set.of(), Set.of(), Set.of(EXPERIMENTAL))))) {
            experimentalTypes.remove(typeName(block));
        }
        StringBuilder stableListing = new StringBuilder();
        StringBuilder experimentalListing = new StringBuilder();
        for (String block : blocks(full)) {
            String name = typeName(block);
            boolean isExperimental = experimentalTypes.contains(name)
                    || experimentalTypes.stream().anyMatch(owner -> name.startsWith(owner + "$"));
            (isExperimental ? experimentalListing : stableListing).append(block).append("\n\n");
        }
        String stable = stableListing.toString();
        String experimental = experimentalListing.toString();

        boolean matches = sync(apiDir.resolve(module + ".api"), stable, update);
        matches &= sync(apiDir.resolve(module + ".experimental.api"), experimental, update);
        if (!matches) {
            System.err.println("The public API changed. Review the difference above, then regenerate the"
                    + " listings with: ./mvnw -B -DskipTests -Dapi.listing.mode=update clean verify");
            System.exit(1);
        }
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
    private static String typeName(String block) {
        String header = block.substring(0, block.indexOf('\n') < 0 ? block.length() : block.indexOf('\n'));
        String afterKeyword = header.substring(header.indexOf(" class ") + " class ".length());
        return afterKeyword.split(" ")[0];
    }

    private static String dump(List<File> classFiles, AbiFilters filters) {
        StringBuilder listing = new StringBuilder();
        AbiTools.INSTANCE.getV2().printJvmDump(listing, classFiles, filters);
        return listing.toString();
    }

    /** Writes the listing in update mode. Otherwise reports whether the committed file matches. */
    private static boolean sync(Path file, String actual, boolean update) throws IOException {
        String committed = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        if (committed.equals(actual)) {
            return true;
        }
        if (update) {
            if (actual.isEmpty()) {
                Files.deleteIfExists(file);
            } else {
                Files.createDirectories(file.getParent());
                Files.writeString(file, actual, StandardCharsets.UTF_8);
            }
            System.out.println("Updated " + file);
            return true;
        }
        Path generated = Files.createTempFile("api-listing", ".api");
        Path empty = Files.createTempFile("api-listing-empty", ".api");
        try {
            Files.writeString(generated, actual, StandardCharsets.UTF_8);
            System.err.println(file + " does not match the compiled classes:");
            System.err.println(AbiTools.INSTANCE.filesDiff((Files.exists(file) ? file : empty).toFile(), generated.toFile()));
        } finally {
            Files.delete(generated);
            Files.delete(empty);
        }
        return false;
    }
}
