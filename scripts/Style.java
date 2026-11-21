//DEPS com.pinterest.ktlint:ktlint-cli:all@1.8.0

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Checks the code style: ktlint (configured by
 * .editorconfig) and the license header from license/copyright.txt on every Kotlin source.
 *
 * <pre>
 * jpm run lint      # check, exit 1 on problems
 * jpm run format    # fix: ktlint --format, then add or update the headers
 * </pre>
 */
public final class Style {
    static final List<String> MODULES = List.of("paperweight-lib", "paperweight-core", "jpm-paperweight");

    public static void main(String[] args) throws Exception {
        boolean format = List.of(args).contains("--format");
        Path root = Path.of(System.getenv().getOrDefault("JPM_PROJECT_DIR", ".")).toAbsolutePath();
        List<Path> sources = new ArrayList<>();
        for (String module : MODULES) {
            for (String set : List.of("main", "test")) {
                Path dir = root.resolve(module).resolve("src").resolve(set).resolve("kotlin");
                if (!Files.isDirectory(dir)) continue;
                try (Stream<Path> s = Files.walk(dir)) {
                    s.filter(p -> p.toString().endsWith(".kt")).sorted().forEach(sources::add);
                }
            }
        }

        int problems = ktlint(root, sources, format);

        String header = header(Files.readString(root.resolve("license/copyright.txt"), StandardCharsets.UTF_8));
        for (Path f : sources) {
            String text = Files.readString(f, StandardCharsets.UTF_8);
            String fixed = withHeader(text, header);
            if (fixed.equals(text)) continue;
            if (format) {
                Files.writeString(f, fixed, StandardCharsets.UTF_8);
                System.out.println("header updated: " + root.relativize(f));
            } else {
                System.out.println(root.relativize(f) + ": missing or outdated license header (jpm run format fixes it)");
                problems++;
            }
        }
        if (problems > 0 && !format) {
            System.out.println(problems + " style problem(s)");
            System.exit(1);
        }
    }

    /** Runs ktlint in a child JVM (it exits the JVM it runs in), on the same classpath as this script. */
    static int ktlint(Path root, List<Path> sources, boolean format) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add("com.pinterest.ktlint.Main");
        cmd.add("--relative");
        if (format) cmd.add("--format");
        for (Path f : sources) cmd.add(root.relativize(f).toString());
        Process p = new ProcessBuilder(cmd).directory(root.toFile()).inheritIO().start();
        int code = p.waitFor();
        return format ? 0 : (code == 0 ? 0 : 1);
    }

    /** The header comment level-headered wrote: the copyright text as a block comment. */
    static String header(String copyright) {
        StringBuilder sb = new StringBuilder("/*\n");
        for (String line : copyright.strip().split("\n", -1)) {
            String l = line.stripTrailing();
            sb.append(l.isEmpty() ? " *" : " * " + l).append('\n');
        }
        return sb.append(" */\n").toString();
    }

    /** The source with {@code header} as its first comment, replacing a license comment already there. */
    static String withHeader(String text, String header) {
        String body = text;
        if (body.startsWith("/*")) {
            int end = body.indexOf("*/");
            // only a license comment is replaced; documentation stays
            if (end > 0 && body.substring(0, end).contains("Copyright")) body = body.substring(end + 2);
        }
        body = body.replaceFirst("^\\s*", "");
        return header + "\n" + body;
    }
}
