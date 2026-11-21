import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Publishes the plugins to a Maven repository, as the Gradle build's maven-publish and java-gradle-plugin did:
 * each plugin jar (the bundle, paperweight-lib and the dependencies shaded in), its sources, a POM without
 * dependencies (everything is in the jar; Gradle provides the rest), and a marker artifact per plugin id, so
 * {@code plugins { id("io.papermc.paperweight.patcher") version "..." }} finds it.
 *
 * <pre>
 * jpm run publish https://artifactory.papermc.io/artifactory/snapshots/
 * jpm run publish file:///tmp/repo
 * </pre>
 *
 * Credentials come from JPM_PUBLISH_USERNAME and JPM_PUBLISH_PASSWORD. The version is the project's, or
 * JPM_VERSION_OVERRIDE. Snapshot versions are uploaded under their plain -SNAPSHOT names; the repository
 * (Artifactory) keeps the timestamped copies and metadata.
 */
public final class Publish {
    static final String GROUP = "io.papermc.paperweight";
    static final String REPO_URL = "https://github.com/PaperMC/paperweight";

    /** The published modules and the plugin ids each provides (gradlePlugin { plugins { ... } } in the Gradle build). */
    static final Map<String, List<String>> PLUGINS = new LinkedHashMap<>();
    static {
        PLUGINS.put("paperweight-core", List.of("io.papermc.paperweight.core", "io.papermc.paperweight.patcher"));
    }

    static final Map<String, String> DESCRIPTIONS = Map.of(
            "paperweight-core", "Gradle plugin for developing Paper Server and derivatives");

    static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    public static void main(String[] args) throws Exception {
        List<String> rest = new ArrayList<>(List.of(args));
        boolean noBuild = rest.remove("--no-build");
        if (rest.size() != 1) {
            System.err.println("usage: jpm run publish [--no-build] <repository URL>");
            System.exit(2);
        }
        String repo = rest.get(0).endsWith("/") ? rest.get(0) : rest.get(0) + "/";
        Path root = Path.of(System.getenv().getOrDefault("JPM_PROJECT_DIR", ".")).toAbsolutePath();
        String version = System.getenv("JPM_VERSION");
        if (version == null || version.isBlank()) throw new IllegalStateException("JPM_VERSION is not set; run this with jpm run publish");

        if (!noBuild) {
            String jpm = System.getenv().getOrDefault("JPM_EXE", "jpm");
            int code = new ProcessBuilder(jpm, "build").directory(root.toFile()).inheritIO().start().waitFor();
            if (code != 0) System.exit(code);
        }

        for (Map.Entry<String, List<String>> e : PLUGINS.entrySet()) {
            String module = e.getKey();
            Path jar = root.resolve(module).resolve("dist").resolve(module + "-" + version + "-bundle.jar");
            if (!Files.isRegularFile(jar)) throw new IllegalStateException(jar + " is missing; run jpm build first");
            String base = GROUP.replace('.', '/') + "/" + module + "/" + version + "/" + module + "-" + version;
            upload(repo, base + ".jar", Files.readAllBytes(jar));
            upload(repo, base + "-sources.jar", sources(root, module));
            upload(repo, base + ".pom", pom(module, version).getBytes(StandardCharsets.UTF_8));
            for (String id : e.getValue()) {
                String marker = id.replace('.', '/') + "/" + id + ".gradle.plugin/" + version + "/" + id + ".gradle.plugin-" + version + ".pom";
                upload(repo, marker, markerPom(id, module, version).getBytes(StandardCharsets.UTF_8));
            }
        }
        System.out.println("Published paperweight " + version + " to " + repo);
    }

    /** The module's sources and paperweight-lib's, which its jar contains too. */
    static byte[] sources(Path root, String module) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (String m : List.of(module, "paperweight-lib")) {
                for (String dir : List.of("src/main/kotlin", "src/main/java", "src/main/resources")) {
                    Path d = root.resolve(m).resolve(dir);
                    if (!Files.isDirectory(d)) continue;
                    try (Stream<Path> files = Files.walk(d)) {
                        for (Path f : files.filter(Files::isRegularFile).sorted().toList()) {
                            String name = d.relativize(f).toString().replace('\\', '/');
                            if (!seen.add(name)) continue;
                            ZipEntry entry = new ZipEntry(name);
                            entry.setTime(315532800000L); // reproducible: 1980-01-01
                            zip.putNextEntry(entry);
                            zip.write(Files.readAllBytes(f));
                            zip.closeEntry();
                        }
                    }
                }
            }
        }
        return out.toByteArray();
    }

    static String pom(String module, String version) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <name>paperweight</name>
                  <description>%s</description>
                  <url>%s</url>
                  <inceptionYear>2020</inceptionYear>
                  <licenses>
                    <license>
                      <name>LGPLv2.1</name>
                      <url>%s/blob/master/license/LGPLv2.1.txt</url>
                      <distribution>repo</distribution>
                    </license>
                  </licenses>
                  <developers>
                    <developer>
                      <id>DenWav</id>
                      <name>Kyle Wood</name>
                      <email>kyle@denwav.dev</email>
                      <url>https://github.com/DenWav</url>
                    </developer>
                  </developers>
                  <scm>
                    <connection>scm:git:%s.git</connection>
                    <developerConnection>scm:git:git@github.com:PaperMC/paperweight.git</developerConnection>
                    <url>%s</url>
                  </scm>
                  <issueManagement>
                    <system>GitHub</system>
                    <url>%s/issues</url>
                  </issueManagement>
                </project>
                """.formatted(GROUP, module, version, DESCRIPTIONS.get(module), REPO_URL, REPO_URL, REPO_URL, REPO_URL, REPO_URL);
    }

    /** What java-gradle-plugin publishes for a plugin id: a POM that depends on the plugin's artifact. */
    static String markerPom(String id, String module, String version) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s.gradle.plugin</artifactId>
                  <version>%s</version>
                  <packaging>pom</packaging>
                  <dependencies>
                    <dependency>
                      <groupId>%s</groupId>
                      <artifactId>%s</artifactId>
                      <version>%s</version>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(id, id, version, GROUP, module, version);
    }

    /** Uploads a file and its checksums. */
    static void upload(String repo, String path, byte[] data) throws Exception {
        put(repo, path, data);
        for (String[] alg : new String[][]{{"MD5", "md5"}, {"SHA-1", "sha1"}, {"SHA-256", "sha256"}, {"SHA-512", "sha512"}}) {
            String sum = HexFormat.of().formatHex(MessageDigest.getInstance(alg[0]).digest(data));
            put(repo, path + "." + alg[1], sum.getBytes(StandardCharsets.US_ASCII));
        }
        System.out.println("  " + path);
    }

    static void put(String repo, String path, byte[] data) throws Exception {
        URI uri = URI.create(repo + path);
        if ("file".equals(uri.getScheme())) {
            Path target = Path.of(uri);
            Files.createDirectories(target.getParent());
            Files.write(target, data);
            return;
        }
        HttpRequest.Builder req = HttpRequest.newBuilder(uri).PUT(HttpRequest.BodyPublishers.ofByteArray(data));
        String user = System.getenv("JPM_PUBLISH_USERNAME"), password = System.getenv("JPM_PUBLISH_PASSWORD");
        if (user != null && password != null) {
            req.header("Authorization", "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8)));
        }
        HttpResponse<String> res = HTTP.send(req.build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw new IOException("PUT " + uri + " failed with HTTP " + res.statusCode() + ": " + res.body());
        }
    }
}
