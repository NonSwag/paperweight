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

import io.sigpipe.jbsdiff.Diff
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class UserdevTest {
    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun Path.put(rel: String, bytes: ByteArray) = resolve(rel).also { it.parent.createDirectories() }.writeBytes(bytes)

    private fun Path.put(rel: String, text: String) = resolve(rel).also { it.parent.createDirectories() }.writeText(text)

    private val original = "vanilla server classes".repeat(50).toByteArray()
    private val patched = "paper server classes, patched".repeat(50).toByteArray()

    /** A repository with what a Paper dev bundle (data version 8) needs, all local. */
    private fun world(tmp: Path, dataVersion: Int = 8): Path {
        val repo = tmp.resolve("repo")
        val repoUrl = repo.toUri().toString()
        val vanilla = zip("META-INF/versions/1.0/server-1.0.jar" to original)
        tmp.put("mojang/server.jar", vanilla)
        val patch = ByteArrayOutputStream().also { Diff.diff(original, patched, it) }.toByteArray()
        val paperclip = zip(
            "META-INF/patches.list" to
                "versions\t${sha256(
                    original
                )}\t${sha256(patch)}\t${sha256(patched)}\t1.0/server-1.0.jar\t1.0/server-1.0.jar.patch\t1.0/paper-1.0.jar\n".toByteArray(),
            "META-INF/download-context" to "${sha256(vanilla)}\t${tmp.resolve("mojang/server.jar").toUri()}\tmojang_1.0.jar".toByteArray(),
            "META-INF/versions/1.0/server-1.0.jar.patch" to patch,
        )
        val config = """{ "minecraftVersion": "1.0", "mojangMappedPaperclipFile": "data/paperclip-mojang.jar", "patchDir": "patches",
            "libraryRepositories": ["$repoUrl"], "pluginRemapArgs": [] }"""
        val bundle = zip(
            "data-version.txt" to "$dataVersion\n".toByteArray(),
            "config.json" to config.toByteArray(),
            "data/paperclip-mojang.jar" to paperclip
        )
        for (v in listOf("1.0.build.1-stable", "1.0.build.2-stable")) {
            repo.put("io/papermc/paper/dev-bundle/$v/dev-bundle-$v.zip", bundle)
            repo.put(
                "io/papermc/paper/dev-bundle/$v/dev-bundle-$v.module",
                """{ "formatVersion": "1.1", "variants": [
                  { "name": "devBundle", "attributes": {} },
                  { "name": "serverCompileClasspath", "attributes": { "org.gradle.usage": "java-api" }, "dependencies": [
                    { "group": "io.papermc.paper", "module": "paper-api", "version": { "requires": "$v" } },
                    { "group": "org.leaf", "module": "bom", "version": { "requires": "1.3" }, "attributes": { "org.gradle.category": "platform" } },
                    { "group": "org.leaf", "module": "common" },
                    { "group": "net.kyori", "module": "ansi" },
                    { "group": "io.papermc", "module": "mache", "version": { "requires": "1.0+build.2" } } ] } ] }"""
            )
            repo.put(
                "io/papermc/paper/paper-api/$v/paper-api-$v.module",
                """{ "formatVersion": "1.1", "variants": [ { "name": "apiElements", "attributes": { "org.gradle.usage": "java-api" }, "dependencies": [
                    { "group": "net.kyori", "module": "adventure-bom", "version": { "requires": "5.2.0" }, "attributes": { "org.gradle.category": "platform" } } ] } ] }"""
            )
        }
        repo.put(
            "io/papermc/paper/dev-bundle/maven-metadata.xml",
            "<metadata><versioning><versions><version>1.0.build.1-stable</version><version>1.0.build.2-stable</version>" +
                "<version>2.0.build.1-alpha</version></versions></versioning></metadata>"
        )
        repo.put(
            "org/leaf/bom/1.3/bom-1.3.pom",
            pom(
                "org.leaf",
                "bom",
                "1.3",
                "<dependency><groupId>org.leaf</groupId><artifactId>common</artifactId><version>\${project.version}</version></dependency>"
            )
        )
        repo.put(
            "net/kyori/adventure-bom/5.2.0/adventure-bom-5.2.0.pom",
            pom(
                "net.kyori",
                "adventure-bom",
                "5.2.0",
                "<dependency><groupId>net.kyori</groupId><artifactId>ansi</artifactId><version>\${adventure}</version></dependency>",
                "<properties><adventure>5.2.0</adventure></properties>"
            )
        )
        repo.put(
            "io/papermc/mache/1.0%2Bbuild.2/mache-1.0%2Bbuild.2.module".replace("%2B", "+"),
            """{ "formatVersion": "1.1", "variants": [ { "name": "serverCompileClasspath", "attributes": {}, "dependencies": [
                { "group": "com.mojang", "module": "brigadier", "version": { "requires": "1.3.11" } },
                { "group": "io.netty", "module": "netty-transport-native-epoll", "version": { "requires": "4.2.0" },
                  "thirdPartyCompatibility": { "artifactSelector": { "name": "netty-transport-native-epoll", "type": "jar", "classifier": "linux-x86_64" } } } ] } ] }"""
        )
        return repo
    }

    private fun pom(g: String, a: String, v: String, managed: String, extra: String = "") =
        "<project><modelVersion>4.0.0</modelVersion><groupId>$g</groupId><artifactId>$a</artifactId><version>$v</version>$extra" +
            "<dependencyManagement><dependencies>$managed</dependencies></dependencyManagement></project>"

    @Test
    fun `a dev bundle becomes the patched server jar and its libraries`(@TempDir tmp: Path) {
        val repo = world(tmp)
        val userdev = Userdev(tmp.resolve("home"), Repositories(listOf(repo.toUri().toString())))
        assertEquals("1.0.build.2-stable", userdev.version("latest"), "the newest release, not the alpha")
        assertEquals("1.0.build.2-stable", userdev.version("1.0"), "a Minecraft version: its newest stable build")
        assertEquals("1.0.build.1-stable", userdev.version("1.0.build.1-stable"))

        val r = userdev.resolve("1.0")
        assertEquals("1.0.build.2-stable", r.version)
        assertTrue(Path.of(r.jars.single()).readBytes().contentEquals(patched), "the server jar is the vanilla one with the patch applied")
        assertEquals(
            mapOf(
                "io.papermc.paper:paper-api" to "1.0.build.2-stable",
                "com.mojang:brigadier" to "1.3.11",
                "io.netty:netty-transport-native-epoll:linux-x86_64" to "4.2.0",
                "org.leaf:common" to "1.3",
                "net.kyori:ansi" to "5.2.0",
            ),
            r.dependencies,
        )
        // the second time comes from the cache, without the repository
        tmp.resolve("repo").toFile().deleteRecursively()
        assertEquals(r, Userdev(tmp.resolve("home"), Repositories(listOf(repo.toUri().toString()))).resolve("1.0.build.2-stable"))
    }

    @Test
    fun `older dev bundles are refused`(@TempDir tmp: Path) {
        val repo = world(tmp, dataVersion = 7)
        val e = assertFailsWith<PaperweightException> { Userdev(tmp.resolve("home"), Repositories(listOf(repo.toUri().toString()))).resolve("1.0") }
        assertTrue(e.message!!.contains("data version 7"), e.message)
    }

    @Test
    fun `a broken patch is caught by its checksums`(@TempDir tmp: Path) {
        val repo = world(tmp)
        // Mojang's jar changed: the vanilla server checksum in paperclip's download-context no longer matches
        tmp.put("mojang/server.jar", zip("META-INF/versions/1.0/server-1.0.jar" to "something else".toByteArray()))
        val e = assertFailsWith<PaperweightException> { Userdev(tmp.resolve("home"), Repositories(listOf(repo.toUri().toString()))).resolve("1.0") }
        assertTrue(e.message!!.contains("Checksum mismatch"), e.message)
    }
}
