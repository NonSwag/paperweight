/*
 * paperweight is build tooling for the PaperMC project.
 *
 * Copyright (c) 2023 Kyle Wood (DenWav)
 *                    Contributors
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation;
 * version 2.1 only, no later versions.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 * USA
 */

package io.papermc.paperweight.jpm.core

import codechicken.diffpatch.cli.PatchOperation
import codechicken.diffpatch.util.archiver.ArchiveFormat
import com.google.gson.Gson
import com.google.gson.JsonObject
import io.papermc.paperweight.jpm.Net
import io.papermc.paperweight.jpm.PaperweightException
import io.papermc.paperweight.jpm.Repositories
import io.papermc.paperweight.jpm.log
import java.io.PrintStream
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.cadixdev.at.AccessTransformSet
import org.cadixdev.at.io.AccessTransformFormats

const val MC_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
const val MC_LIBRARY_URL = "https://libraries.minecraft.net/"
const val MAVEN_CENTRAL_URL = "https://repo.maven.apache.org/maven2/"
const val PAPER_MAVEN_REPO_URL = "https://repo.papermc.io/repository/maven-public/"
const val JST = "io.papermc.jst:jst-cli-bundle:1.0.68+paper.2-SNAPSHOT"

/**
 * paperweight-core's settings, from the server module's `"extensions": { "paperweight": { ... } }` (paths relative to that
 * module), with the Gradle plugin's defaults for Paper's layout.
 */
data class CoreConfig(
    val root: Path,
    val serverDir: Path,
    val minecraftVersion: String,
    val mache: MavenArtifact,
    val macheRepo: String,
    val sourcePatches: Path,
    val resourcePatches: Path,
    val featurePatches: Path,
    val rejects: Path,
    val additionalAts: Path,
    val devImports: Path,
    val gitFilePatches: Boolean,
    val filterPatches: Boolean,
    /** group:artifact:version of the server's runtime classpath, whose sources patches may import from. */
    val runtimeLibraries: List<String>,
) {
    val cache: Path get() = root.resolve(".cache/paperweight")
    val minecraftSources: Path get() = serverDir.resolve("src/minecraft/java")
    val minecraftResources: Path get() = serverDir.resolve("src/minecraft/resources")
}

/** One of paperweight's setup steps: runs [work] unless [output] is there from a run with the same [key]. */
private fun step(name: String, output: Path, key: String, work: () -> Unit) {
    val marker = output.resolveSibling(output.name + ".key")
    if (output.exists() && marker.isRegularFile() && marker.readText() == key) return
    log(name)
    work()
    marker.parent.createDirectories()
    marker.writeText(key)
}

private fun hash(vararg parts: Any?): String {
    val md = MessageDigest.getInstance("SHA-256")
    for (p in parts) {
        when (p) {
            is Path -> if (p.isRegularFile()) md.update(Files.readAllBytes(p)) else md.update(p.toString().toByteArray())
            else -> md.update(p.toString().toByteArray())
        }
        md.update(0)
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/** The base the patches apply to: Minecraft's sources (decompiled, with mache's patches, ATs and library imports) and resources. */
class Setup(private val cfg: CoreConfig) {
    private val gson = Gson()
    private val mc = cfg.cache.resolve("minecraft").resolve(cfg.minecraftVersion)
    private val tools = cfg.cache.resolve("tools")
    private val macheDir = cfg.cache.resolve("mache").resolve(cfg.mache.version)

    val baseSources: Path get() = macheDir.resolve("base/sources")
    val baseResources: Path get() = macheDir.resolve("base/resources")
    val minecraftLibraries: Path get() = mc.resolve("libraries")

    // ------------------------------------------------------------------ Minecraft

    fun bundlerJar(): Path {
        val out = mc.resolve("server-bundler.jar")
        if (out.isRegularFile()) return out
        log("Downloading the Minecraft ${cfg.minecraftVersion} server")
        val manifest = gson.fromJson(Net.getString(MC_MANIFEST_URL) ?: throw PaperweightException("No $MC_MANIFEST_URL"), JsonObject::class.java)
        val entry = manifest.getAsJsonArray("versions").map { it.asJsonObject }.firstOrNull { it["id"].asString == cfg.minecraftVersion }
            ?: throw PaperweightException("Minecraft ${cfg.minecraftVersion} is not in Mojang's version manifest")
        val versionJson = mc.resolve("version.json")
        Net.download(entry["url"].asString, versionJson, "SHA-1" to entry["sha1"].asString)
        val server = gson.fromJson(versionJson.readText(), JsonObject::class.java).getAsJsonObject("downloads").getAsJsonObject("server")
        Net.download(server["url"].asString, out, "SHA-1" to server["sha1"].asString)
        return out
    }

    /** The server jar inside the bundler, and its libraries (jars in [minecraftLibraries], coordinates in libraries.txt). */
    fun serverJar(): Path {
        val bundler = bundlerJar()
        val out = mc.resolve("server.jar")
        step("Extracting the server from the bundler", out, hash(bundler)) {
            FileSystems.newFileSystem(bundler).use { fs ->
                val root = fs.getPath("/")
                val id = gson.fromJson(root.resolve("version.json").readText(), JsonObject::class.java)["id"].asString
                val versions = root.resolve("META-INF/versions.list").readText().lines().filter { it.isNotBlank() }
                    .map { it.split('\t') }.associate { it[1] to it[2] }
                Files.copy(root.resolve("META-INF/versions/${versions[id]}"), out, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                minecraftLibraries.deleteRecursive()
                root.resolve("META-INF/libraries").copyRecursivelyTo(minecraftLibraries)
                val libs = root.resolve("META-INF/libraries.list").readText().lines().filter { it.isNotBlank() }.map { it.split('\t')[1] }
                mc.resolve("libraries.txt").writeText(libs.joinToString("\n", postfix = "\n"))
            }
        }
        return out
    }

    fun minecraftLibraryJars(): List<Path> {
        serverJar()
        return minecraftLibraries.filesMatchingRecursive("*.jar")
    }

    fun minecraftLibraryCoordinates(): List<String> {
        serverJar()
        return mc.resolve("libraries.txt").readText().lines().filter { it.isNotBlank() }
    }

    // ------------------------------------------------------------------ mache

    fun macheZip(): Path {
        val out = macheDir.resolve("mache.zip")
        if (!out.isRegularFile()) {
            Repositories(listOf(cfg.macheRepo)).artifact(cfg.mache.copy(extension = "zip"), tools).let {
                out.parent.createDirectories()
                Files.copy(it, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }
        return out
    }

    fun macheMeta(): JsonObject = ZipFile(macheZip().toFile()).use { zip ->
        gson.fromJson(String(zip.getInputStream(zip.getEntry("mache.json")).readBytes()), JsonObject::class.java)
    }

    private fun macheRepositories(meta: JsonObject): Repositories =
        Repositories(meta.getAsJsonArray("repositories").map { it.asJsonObject["url"].asString } + listOf(MAVEN_CENTRAL_URL, PAPER_MAVEN_REPO_URL))

    private fun macheTool(meta: JsonObject, kind: String): List<Path> {
        val repos = macheRepositories(meta)
        return meta.getAsJsonObject("dependencies").getAsJsonArray(kind)?.map { it.asJsonObject }?.map { a ->
            repos.artifact(
                MavenArtifact(
                    a["group"].asString,
                    a["name"].asString,
                    a["version"].asString,
                    a["classifier"]?.takeIf { !it.isJsonNull }?.asString,
                    a["extension"]?.takeIf { !it.isJsonNull }?.asString
                ),
                tools
            )
        } ?: emptyList()
    }

    fun jst(): List<Path> = listOf(Repositories(listOf(PAPER_MAVEN_REPO_URL, MAVEN_CENTRAL_URL)).artifact(MavenArtifact.parse(JST), tools))

    /** Codebook: the server jar with parameter names, unpicked constants and the like. */
    fun remappedJar(): Path {
        val meta = macheMeta()
        val server = serverJar()
        val out = macheDir.resolve("codebook-minecraft.jar")
        val codebook = macheTool(meta, "codebook")
        val constants = macheTool(meta, "constants").singleOrNull()
        val libs = minecraftLibraryJars()
        step("Running codebook", out, hash(server, meta.toString(), codebook, constants, libs)) {
            val temp = macheDir.resolve("codebook-tmp").also {
                it.deleteRecursive()
                it.createDirectories()
            }
            Files.deleteIfExists(out)
            val args = meta.getAsJsonArray("remapperArgs").map { it.asString }.map { arg ->
                arg.replace("{tempDir}", temp.toAbsolutePath().toString())
                    .replace("{constantsFile}", constants?.toAbsolutePath()?.toString() ?: throw PaperweightException("mache names no constants"))
                    .replace("{output}", out.toAbsolutePath().toString())
                    .replace("{input}", server.toAbsolutePath().toString())
                    .replace("{inputClasspath}", libs.joinToString(":") { it.toAbsolutePath().toString() })
            }
            runJar(javaExecutable(), codebook, temp, macheDir.resolve("codebook.log"), listOf("-Xmx2G"), args)
        }
        return out
    }

    /** The decompiled sources (a jar of .java files). */
    fun decompiledJar(): Path {
        val meta = macheMeta()
        val input = remappedJar()
        val out = macheDir.resolve("decompiled.jar")
        val decompiler = macheTool(meta, "decompiler")
        val libs = minecraftLibraryJars()
        val memory = System.getenv("PAPERWEIGHT_DECOMPILE_MEMORY") ?: "4G"
        step(
            "Decompiling Minecraft (this takes a few minutes)",
            out,
            hash(input, meta.getAsJsonArray("decompilerArgs").toString(), decompiler, libs)
        ) {
            val work = macheDir.resolve("decompile-tmp").also {
                it.deleteRecursive()
                it.createDirectories()
            }
            Files.deleteIfExists(out)
            val cfgFile = work.resolve("libraries.cfg")
            cfgFile.writeText(libs.joinToString("") { "-e=${it.toAbsolutePath()}${System.lineSeparator()}" })
            val args = meta.getAsJsonArray("decompilerArgs").map { it.asString } +
                listOf("-cfg", cfgFile.toAbsolutePath().toString(), input.toAbsolutePath().toString(), out.toAbsolutePath().toString())
            runJar(javaExecutable(), decompiler, work, macheDir.resolve("decompile.log"), listOf("-Xmx$memory"), args)
            FileSystems.newFileSystem(out).use { Files.deleteIfExists(it.getPath("META-INF", "MANIFEST.MF")) }
        }
        return out
    }

    // ------------------------------------------------------------------ access transformers and library imports

    /** paper.at and the ATs feature patches declare in their "== AT ==" section, merged. */
    fun mergedAts(): Path {
        val collected = sortedSetOf<String>()
        for (patch in cfg.featurePatches.filesMatchingRecursive("*.patch")) {
            var reading = false
            for (line in Files.readAllLines(patch)) {
                if (line.startsWith("diff --git a/") || line.startsWith("Co-authored-by: ", true)) break
                if (reading && line.isNotBlank() && !line.startsWith('#')) collected += line
                if (line.startsWith("== AT ==")) reading = true
            }
        }
        val collectedFile = cfg.cache.resolve("ats/collected.at").also { it.parent.createDirectories() }
        collectedFile.writeText(collected.joinToString("\n", postfix = "\n"))
        val merged = AccessTransformSet.create()
        for (f in listOf(cfg.additionalAts, collectedFile)) if (f.isRegularFile()) merged.merge(AccessTransformFormats.FML.read(f))
        val out = cfg.cache.resolve("ats/merged.at")
        AccessTransformFormats.FML.writeLF(out, merged)
        return out
    }

    /** Sources jars of Minecraft's and the server's libraries, which patches may import classes from. */
    fun librarySources(): List<Path> {
        val dir = cfg.cache.resolve("library-sources")
        val out = ArrayList<Path>()
        val sets = listOf(
            minecraftLibraryCoordinates() to Repositories(listOf(MC_LIBRARY_URL, MAVEN_CENTRAL_URL)),
            cfg.runtimeLibraries to Repositories(listOf(MAVEN_CENTRAL_URL, PAPER_MAVEN_REPO_URL)),
        )
        for ((coords, repos) in sets) {
            for (c in coords.distinct()) {
                val a = MavenArtifact.parse(c).let { it.copy(classifier = "sources", extension = "jar") }
                val missing = dir.resolve(a.group).resolve(a.name).resolve(a.version).resolve(a.fileName + ".missing")
                if (missing.exists()) continue
                try {
                    out.add(repos.artifact(a, dir))
                } catch (e: PaperweightException) {
                    missing.parent.createDirectories()
                    missing.writeText(e.message ?: "")
                }
            }
        }
        return out.distinct()
    }

    /** Library classes that patches change (`+++ b/<path>`) or dev-imports.txt names, copied from the sources jars. */
    fun importLibraryFiles(): Path {
        val out = cfg.cache.resolve("library-imports")
        out.deleteRecursive()
        out.createDirectories()
        val patches = cfg.sourcePatches.filesMatchingRecursive("*.patch") + cfg.featurePatches.filesMatchingRecursive("*.patch")
        if (patches.isEmpty()) return out
        val libs = librarySources()
        val index = HashMap<String, Path>() // source file path -> sources jar
        for (lib in libs) {
            ZipFile(lib.toFile()).use { zip ->
                zip.entries().asSequence().filter { it.name.endsWith(".java") }.forEach { index.putIfAbsent(it.name, lib) }
            }
        }
        val wanted = HashMap<String, Path>()
        if (cfg.devImports.isRegularFile()) {
            for (line in Files.readAllLines(cfg.devImports)) {
                if (line.startsWith('#')) continue
                val parts = line.split(' ')
                if (parts.size != 2) continue
                val lib = libs.firstOrNull { it.name.startsWith(parts[0]) }
                    ?: throw PaperweightException("${cfg.devImports}: no library ${parts[0]} for ${parts[1]}")
                wanted[parts[1].removeSuffix(".java").replace('.', '/') + ".java"] = lib
            }
        }
        for (patch in patches) {
            for (line in Files.readAllLines(patch)) {
                if (!line.startsWith("+++ b/")) continue
                val path = line.substring(6)
                index[path]?.let { wanted.putIfAbsent(path, it) }
            }
        }
        for ((lib, files) in wanted.entries.groupBy({ it.value }, { it.key })) {
            ZipFile(lib.toFile()).use { zip ->
                for (f in files) {
                    val target = out.resolve(f)
                    if (target.exists()) continue
                    target.parent.createDirectories()
                    zip.getInputStream(zip.getEntry(f) ?: continue).use { Files.copy(it, target) }
                }
            }
        }
        log("Imported ${wanted.size} classes from library sources")
        return out
    }

    // ------------------------------------------------------------------ the base Git repositories

    /** Minecraft's sources as a Git repository: tags ROOT, Vanilla, Mache, ATs and Imports, in that order. */
    fun setupSources(): Path {
        val decompiled = decompiledJar()
        val mache = macheZip()
        val ats = mergedAts()
        val imports = importLibraryFiles()
        val key = hash(decompiled, mache, ats, treeHash(imports))
        step("Setting up the Minecraft sources (mache patches, access transformers, library imports)", baseSources, key) {
            val git = initRepo(baseSources)
            copyFromZip(decompiled, baseSources) { it.endsWith(".java") }
            commitAndTag(git, "Vanilla")
            val result = PatchOperation.builder()
                .logTo(PrintStream(System.err))
                .basePath(baseSources)
                .outputPath(baseSources)
                .patchesPath(mache, ArchiveFormat.ZIP)
                .patchesPrefix("patches")
                .level(codechicken.diffpatch.util.LogLevel.WARN)
                .ignorePrefix(".git")
                .build()
                .operate()
            commitAndTag(git, "Mache")
            if (result.exit != 0) throw PaperweightException("Failed to apply ${result.summary.failedMatches} mache patches")
            applyAts(baseSources, baseSources, ats, false)
            commitAndTag(git, "ATs", "paper ATs")
            imports.copyRecursivelyTo(baseSources)
            commitAndTag(git, "Imports", "paper Imports")
        }
        return baseSources
    }

    /** Minecraft's resources (everything in the server jar but classes) as a Git repository: tags ROOT and Vanilla. */
    fun setupResources(): Path {
        val server = serverJar()
        step("Setting up the Minecraft resources", baseResources, hash(server)) {
            val git = initRepo(baseResources)
            copyFromZip(server, baseResources) { !it.endsWith(".class") && !it.endsWith("/") }
            commitAndTag(git, "Vanilla")
        }
        return baseResources
    }

    /** JST applies access transformers to sources: a folder, or a single file when [singleFile]. */
    fun applyAts(input: Path, output: Path, atFile: Path, singleFile: Boolean) {
        val work = cfg.cache.resolve("jst-work").also {
            it.deleteRecursive()
            it.createDirectories()
        }
        val format = if (singleFile) "FILE" else "FOLDER"
        val args = listOf(
            "--in-format=$format",
            "--out-format=$format",
            "--enable-accesstransformers",
            "--access-transformer=${atFile.toAbsolutePath()}",
            "--access-transformer-inherit-method=true",
            "--hidden-prefix=.git",
        ) + minecraftLibraryJars().map { "--classpath=${it.toAbsolutePath()}" } +
            listOf(input.toAbsolutePath().toString(), output.toAbsolutePath().toString())
        runJar(javaExecutable(), jst(), work, work.resolve("log.txt"), listOf("-Xmx1G"), args)
    }

    private fun initRepo(dir: Path): Git {
        dir.deleteRecursive()
        dir.createDirectories()
        val git = Git(dir)
        git("init", "--quiet", "--initial-branch=main").execute()
        git.disableAutoGpgSigningInRepo()
        ident(git, "ROOT")("commit", "--allow-empty", "-m", "ROOT").execute()
        git("tag", "-f", "ROOT").execute()
        return git
    }

    private fun copyFromZip(zip: Path, target: Path, include: (String) -> Boolean) {
        ZipFile(zip.toFile()).use { z ->
            for (e in z.entries()) {
                if (e.isDirectory || !include(e.name)) continue
                val dest = target.resolve(e.name)
                dest.parent.createDirectories()
                val bytes = z.getInputStream(e).readBytes()
                if (e.name.endsWith(".nbt")) {
                    Files.write(dest, bytes) // binary
                } else {
                    var text = String(bytes, Charsets.UTF_8)
                    if (!text.endsWith("\n")) text += "\n"
                    dest.writeText(text)
                }
            }
        }
    }

    private fun treeHash(dir: Path): String =
        if (!dir.isDirectory()) "" else hash(*dir.filesMatchingRecursive("*").map { "${dir.relativize(it)} ${Files.size(it)}" }.toTypedArray())
}

/** Commits everything as [name] (author and committer) and tags it [name]. */
fun commitAndTag(git: Git, name: String, message: String = name) {
    git("add", "--all", ".").execute()
    ident(git, name)("commit", "--allow-empty", "--no-verify", "-m", message).execute()
    git("tag", "-f", name).execute()
}

/** A fixed author, committer and date, so the base repositories are the same on every machine. */
fun ident(git: Git, name: String, date: String = "1997-04-20T13:37:42Z"): Git = git.withEnv(
    mapOf(
        "GIT_AUTHOR_NAME" to name,
        "GIT_AUTHOR_EMAIL" to "noreply+automated@papermc.io",
        "GIT_AUTHOR_DATE" to date,
        "GIT_COMMITTER_NAME" to name,
        "GIT_COMMITTER_EMAIL" to "noreply+automated@papermc.io",
        "GIT_COMMITTER_DATE" to date,
    )
)
