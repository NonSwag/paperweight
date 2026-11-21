/*
 * paperweight is a Gradle plugin for the PaperMC project.
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

package io.papermc.paperweight.jpm

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.sigpipe.jbsdiff.Patch
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import org.w3c.dom.Element

/** What `jpm paperweight resolve server <version>` answers: the format jpm reads (see jpm's ExtensionDeps). */
data class Resolved(
    val version: String,
    val jars: List<String>,
    val dependencies: Map<String, String>,
)

/**
 * paperweight-userdev for jpm: a Paper dev bundle as a dependency. For dev bundle data version 8 (Minecraft 26.1 and
 * newer, which are not obfuscated) the server plugins compile against is the vanilla server jar with the bundle's
 * paperclip patch applied; its libraries are the bundle's (and mache's) serverCompileClasspath.
 */
class Userdev(private val home: Path, private val repositories: Repositories) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    companion object {
        const val GROUP = "io.papermc.paper"
        const val ARTIFACT = "dev-bundle"
        const val SUPPORTED_DATA_VERSION = 8
        const val PAPER_REPOSITORY = "https://repo.papermc.io/repository/maven-public/"
        const val CENTRAL = "https://repo.maven.apache.org/maven2/"
    }

    /**
     * The dev bundle version a spec stands for: an exact version, `latest` (the newest stable build), or a prefix such as
     * `26.3` (the newest 26.3.build.N-stable).
     */
    fun version(spec: String): String {
        val s = spec.trim()
        if (s.isNotEmpty() && s != "latest" && home.resolve(s).resolve("resolved.json").exists()) return s
        val all = repositories.versions(GROUP, ARTIFACT)
        if (all.isEmpty()) throw PaperweightException("No versions of $GROUP:$ARTIFACT in ${repositories.urls}")
        // Paper publishes alpha and beta builds of new Minecraft versions too; latest is the newest stable one
        if (s.isEmpty() || s == "latest") return all.lastOrNull { it.endsWith("-stable") } ?: all.last { !it.contains("SNAPSHOT") }
        if (s in all) return s
        return all.lastOrNull { it.startsWith("$s.") && it.endsWith("-stable") }
            ?: all.lastOrNull { it.startsWith("$s.") }
            ?: throw PaperweightException("No dev bundle version $s (newest: ${all.last()})")
    }

    fun resolve(spec: String): Resolved {
        val version = version(spec)
        val dir = home.resolve(version)
        val cached = dir.resolve("resolved.json")
        if (cached.exists()) {
            val r = gson.fromJson(cached.readText(), Resolved::class.java)
            if (r.jars.all { Path.of(it).exists() }) return r
        }
        dir.createDirectories()
        log("Setting up the Paper dev bundle $version")
        val bundle = dir.resolve("dev-bundle.zip")
        if (!repositories.fetch(GROUP, ARTIFACT, version, "$ARTIFACT-$version.zip", bundle)) {
            throw PaperweightException("$GROUP:$ARTIFACT:$version not found in ${repositories.urls}")
        }
        val module = dir.resolve("dev-bundle.module")
        if (!repositories.fetch(GROUP, ARTIFACT, version, "$ARTIFACT-$version.module", module)) {
            throw PaperweightException("$GROUP:$ARTIFACT:$version has no Gradle module metadata, which names its dependencies")
        }
        val config = ZipFile(bundle.toFile()).use { zip ->
            val dataVersion = zip.text("data-version.txt").trim().toInt()
            if (dataVersion != SUPPORTED_DATA_VERSION) {
                throw PaperweightException(
                    "The dev bundle $version has data version $dataVersion; jpm-paperweight supports $SUPPORTED_DATA_VERSION" +
                        " (Minecraft 26.1 and newer)"
                )
            }
            gson.fromJson(zip.text("config.json"), JsonObject::class.java)
        }
        val minecraft = config["minecraftVersion"].asString
        val server = dir.resolve("paper-server-$version.jar")
        if (!server.exists()) serverJar(bundle, config["mojangMappedPaperclipFile"].asString, minecraft, dir, server)

        val libraryRepos = Repositories(
            (config.getAsJsonArray("libraryRepositories")?.map { it.asString } ?: emptyList()) + repositories.urls
        )
        val deps = dependencies(module, libraryRepos)
        val result = Resolved(version, listOf(server.toString()), deps)
        cached.writeText(gson.toJson(result))
        return result
    }

    /** The vanilla server jar from Mojang, its server jar patched with paperclip's patch, checked at every step. */
    private fun serverJar(bundle: Path, paperclipPath: String, minecraft: String, dir: Path, out: Path) {
        val paperclip = dir.resolve("paperclip.jar")
        ZipFile(bundle.toFile()).use { zip ->
            val e = zip.getEntry(paperclipPath) ?: throw PaperweightException("The dev bundle has no $paperclipPath")
            zip.getInputStream(e).use { Files.copy(it, paperclip, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
        }
        // META-INF/patches.list: "versions <original sha256> <patch sha256> <output sha256> <original> <patch> <output>"
        val (patchLine, download) = ZipFile(paperclip.toFile()).use { zip ->
            zip.text("META-INF/patches.list").lines().first { it.isNotBlank() }.split('\t') to
                zip.text("META-INF/download-context").trim().split('\t')
        }
        if (patchLine.size < 7 || patchLine[0] != "versions") throw PaperweightException("Unexpected paperclip patches.list: $patchLine")

        val vanilla = dir.resolve("vanilla-$minecraft.jar")
        if (!vanilla.exists() || Net.sha256(vanilla) != download[0]) {
            log("Downloading the Minecraft $minecraft server")
            Net.download(download[1], vanilla, "SHA-256" to download[0])
        }
        val original = ZipFile(vanilla.toFile()).use { zip -> zip.bytes("META-INF/versions/${patchLine[4]}") }
        check(original, patchLine[1], "the vanilla server jar")
        val patch = ZipFile(paperclip.toFile()).use { zip -> zip.bytes("META-INF/versions/${patchLine[5]}") }
        check(patch, patchLine[2], "the paperclip patch")
        log("Applying the Paper patch")
        val patched = ByteArrayOutputStream(original.size * 2)
        Patch.patch(original, patch, patched)
        val bytes = patched.toByteArray()
        check(bytes, patchLine[3], "the patched server jar")
        val tmp = out.resolveSibling(out.fileName.toString() + ".tmp")
        tmp.writeBytes(bytes)
        Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        Files.deleteIfExists(paperclip)
    }

    private fun check(bytes: ByteArray, sha256: String, what: String) {
        val actual = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (!actual.equals(sha256, ignoreCase = true)) throw PaperweightException("Checksum mismatch for $what: expected $sha256, got $actual")
    }

    // ------------------------------------------------------------------ dependencies

    /**
     * The serverCompileClasspath variant of the dev bundle: paper-api and the server's own libraries, and mache's
     * variant of the same name (the vanilla libraries). Versions a platform manages (BOMs the variant or a dependency's
     * module metadata names) are filled in from the BOMs.
     */
    fun dependencies(moduleFile: Path, repos: Repositories): Map<String, String> {
        val variant = compileVariant(gson.fromJson(moduleFile.readText(), JsonObject::class.java), "dev bundle")
        val out = LinkedHashMap<String, String>()
        val missing = ArrayList<String>()
        val platforms = ArrayList<Triple<String, String, String>>()
        for (dep in variant.deps()) {
            if (dep.platform) {
                dep.version?.let { platforms += Triple(dep.group, dep.module, it) }
                continue
            }
            if (dep.group == "io.papermc" && dep.module == "mache" && dep.version != null) {
                val macheModule = repos.getString(dep.group, dep.module, dep.version, "${dep.module}-${dep.version}.module")
                    ?: throw PaperweightException("mache ${dep.version} has no module metadata")
                for (m in compileVariant(gson.fromJson(macheModule, JsonObject::class.java), "mache").deps()) {
                    if (!m.platform && m.version != null) out.putIfAbsent(m.key, m.version)
                }
                continue
            }
            if (dep.version == null) missing += dep.key else out.putIfAbsent(dep.key, dep.version)
        }
        if (missing.isNotEmpty()) {
            // the platforms of the dependencies themselves (paper-api brings adventure-bom)
            for ((key, version) in out.toMap()) {
                val (g, a) = key.split(':')
                val text = repos.getString(g, a, version, "$a-$version.module") ?: continue
                val v = runCatching { compileVariant(gson.fromJson(text, JsonObject::class.java), key) }.getOrNull() ?: continue
                for (d in v.deps()) if (d.platform && d.version != null) platforms += Triple(d.group, d.module, d.version)
            }
            val managed = HashMap<String, String>()
            for ((g, a, v) in platforms) managed.putAll(Boms(repos).managed(g, a, v))
            for (key in missing) {
                val v = managed[key.substringBeforeLast(':').takeIf { key.count { it == ':' } > 1 } ?: key]
                    ?: throw PaperweightException("No version for $key: no platform of the dev bundle manages it")
                out.putIfAbsent(key, v)
            }
        }
        return out
    }

    private data class Dep(val group: String, val module: String, val version: String?, val classifier: String?, val platform: Boolean) {
        val key: String get() = "$group:$module" + (classifier?.let { ":$it" } ?: "")
    }

    private fun compileVariant(module: JsonObject, what: String): JsonObject {
        val variants = module.getAsJsonArray("variants").map { it.asJsonObject }
        return variants.firstOrNull { it["name"].asString == "serverCompileClasspath" }
            ?: variants.firstOrNull { it.getAsJsonObject("attributes")?.get("org.gradle.usage")?.asString == "java-api" }
            ?: throw PaperweightException("The $what module metadata has no serverCompileClasspath variant")
    }

    private fun JsonObject.deps(): List<Dep> = (getAsJsonArray("dependencies") ?: JsonArray()).map { it.asJsonObject }.map { d ->
        val version = d.getAsJsonObject("version")?.let { v -> (v["strictly"] ?: v["requires"] ?: v["prefers"])?.asString }
        val classifier = d.getAsJsonObject("thirdPartyCompatibility")?.getAsJsonObject("artifactSelector")?.get("classifier")?.asString
        val platform = d.getAsJsonObject("attributes")?.get("org.gradle.category")?.asString.let { it == "platform" || it == "enforced-platform" }
        Dep(d["group"].asString, d["module"].asString, version, classifier, platform)
    }

    private fun ZipFile.text(name: String): String = String(bytes(name), Charsets.UTF_8)

    private fun ZipFile.bytes(name: String): ByteArray {
        val e = getEntry(name) ?: throw PaperweightException("$name is missing from $this")
        return getInputStream(e).use { it.readBytes() }
    }
}

/** The versions a BOM manages: its dependencyManagement, with properties and imported BOMs. */
class Boms(private val repos: Repositories) {
    fun managed(group: String, artifact: String, version: String, depth: Int = 0): Map<String, String> {
        if (depth > 5) return emptyMap()
        val text = repos.getString(group, artifact, version, "$artifact-$version.pom") ?: return emptyMap()
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }.newDocumentBuilder()
            .parse(text.byteInputStream())
        val root = doc.documentElement
        val props = HashMap<String, String>()
        props["project.version"] = version
        root.child("properties")?.let { p ->
            val nodes = p.childNodes
            for (i in 0 until nodes.length) (nodes.item(i) as? Element)?.let { props[it.tagName] = it.textContent.trim() }
        }
        fun interp(s: String?): String? = s?.let { Regex("\\$\\{([^}]+)}").replace(it) { m -> props[m.groupValues[1]] ?: m.value } }
        val out = LinkedHashMap<String, String>()
        val deps = root.child("dependencyManagement")?.child("dependencies") ?: return out
        val nodes = deps.getElementsByTagName("dependency")
        for (i in 0 until nodes.length) {
            val d = nodes.item(i) as Element
            val g = interp(d.child("groupId")?.textContent?.trim()) ?: continue
            val a = interp(d.child("artifactId")?.textContent?.trim()) ?: continue
            val v = interp(d.child("version")?.textContent?.trim()) ?: continue
            if (d.child("scope")?.textContent?.trim() == "import") {
                managed(g, a, v, depth + 1).forEach { (k, vv) -> out.putIfAbsent(k, vv) }
            } else {
                out.putIfAbsent("$g:$a", v)
            }
        }
        return out
    }

    private fun Element.child(name: String): Element? {
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n is Element && n.tagName == name) return n
        }
        return null
    }
}

fun log(message: String) = System.err.println("  paperweight  $message")
