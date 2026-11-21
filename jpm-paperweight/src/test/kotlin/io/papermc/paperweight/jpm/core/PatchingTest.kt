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

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.cadixdev.at.AccessChange
import org.cadixdev.at.ModifierChange
import org.junit.jupiter.api.io.TempDir

class PatchingTest {
    private fun Path.put(rel: String, text: String) = resolve(rel).also { it.parent.createDirectories() }.writeText(text)

    private fun config(root: Path) = CoreConfig(
        root = root,
        serverDir = root.resolve("server"),
        minecraftVersion = "1.0",
        mache = MavenArtifact.parse("io.papermc:mache:1.0+build.1"),
        macheRepo = "file:/nowhere",
        sourcePatches = root.resolve("server/patches/sources"),
        resourcePatches = root.resolve("server/patches/resources"),
        featurePatches = root.resolve("server/patches/features"),
        rejects = root.resolve("server/patches/rejected"),
        additionalAts = root.resolve("build-data/paper.at"),
        devImports = root.resolve("build-data/dev-imports.txt"),
        gitFilePatches = false,
        filterPatches = true,
        runtimeLibraries = emptyList(),
    )

    @Test
    fun `settings come from the server module's extensions`() {
        val info = Gson().fromJson(
            """{ "projectDir": "/p", "modules": [
              { "name": "paper-api", "dir": "/p/paper-api" },
              { "name": "paper-server", "dir": "/p/paper-server", "extensions": { "paperweight": { "minecraftVersion": "26.3", "mache": "io.papermc:mache:26.3+build.2" } },
                "classpaths": { "runtime": [ { "kind": "maven", "group": "a", "artifact": "b", "version": "1" }, { "kind": "workspace", "artifact": "paper-api" } ] } } ] }""",
            JsonObject::class.java
        )
        val cfg = ConfigLoader.parse(info)
        assertEquals(Path.of("/p/paper-server"), cfg.serverDir)
        assertEquals("26.3+build.2", cfg.mache.version)
        assertEquals(Path.of("/p/paper-server/patches/features"), cfg.featurePatches)
        assertEquals(Path.of("/p/build-data/paper.at"), cfg.additionalAts)
        assertEquals(listOf("a:b:1"), cfg.runtimeLibraries)
    }

    @Test
    fun `snapshot versions and access transformers read as before`() {
        val meta =
            "<snapshotVersions><snapshotVersion><classifier>sources</classifier><extension>jar</extension><value>1.0-1</value></snapshotVersion>" +
                "<snapshotVersion><extension>jar</extension><value>1.0-20260919.111043-2</value></snapshotVersion></snapshotVersions>"
        assertEquals("1.0-20260919.111043-2", snapshotValue(meta, null, "jar"))
        assertEquals("1.0-1", snapshotValue(meta, "sources", "jar"))
        assertEquals(AccessChange.PUBLIC, atFromString("public-f").access)
        assertEquals(ModifierChange.REMOVE, atFromString("public-f").final)
        assertEquals(ModifierChange.ADD, atFromString("private+f").final)
        assertEquals(AccessChange.NONE, atFromString("-f").access)
        assertEquals(MavenArtifact("g", "a", "1", "all", "zip"), MavenArtifact.parse("g:a:1:all@zip"))
    }

    @Test
    fun `file and feature patches apply and rebuild to themselves`(@TempDir tmp: Path) {
        Git.checkForGit()
        val cfg = config(tmp)
        val setup = Setup(cfg)
        // a base repository, as setupSources makes it
        val base = tmp.resolve("base").also { it.createDirectories() }
        base.put("net/minecraft/Server.java", "package net.minecraft;\n\npublic class Server {\n    int ticks;\n}\n")
        val git = Git(base)
        git("init", "--quiet", "--initial-branch=main").execute()
        commitAndTag(git, "Vanilla")
        // a file patch and a feature patch
        cfg.sourcePatches.resolve("net/minecraft").createDirectories()
        // (a unified diff's blank context line is a single space)
        val filePatch = listOf(
            "--- a/net/minecraft/Server.java",
            "+++ b/net/minecraft/Server.java",
            "@@ -1,5 +1,5 @@",
            " package net.minecraft;",
            " ",
            " public class Server {",
            "-    int ticks;",
            "+    public int ticks; // Paper",
            " }",
        ).joinToString("\n", postfix = "\n")
        cfg.sourcePatches.put("net/minecraft/Server.java.patch", filePatch)

        val patching = Patching(cfg, setup)
        assertEquals(1, patching.applyFilePatches(base, cfg.minecraftSources, cfg.sourcePatches, cfg.rejects))
        assertTrue(cfg.minecraftSources.resolve("net/minecraft/Server.java").readText().contains("public int ticks; // Paper"))

        // a feature commit, rebuilt into a feature patch, applies again
        val repo = ident(Git(cfg.minecraftSources), "Dev")
        cfg.minecraftSources.put(
            "net/minecraft/Server.java",
            cfg.minecraftSources.resolve("net/minecraft/Server.java").readText().replace("}", "    void tick() {}\n}")
        )
        repo("add", ".").execute()
        repo("commit", "-m", "Add ticking").execute()
        assertEquals(1, patching.rebuildFeaturePatches(cfg.minecraftSources, cfg.featurePatches))
        assertTrue(Files.list(cfg.featurePatches).use { it.toList() }.single().fileName.toString().endsWith("Add-ticking.patch"))

        patching.applyFilePatches(base, cfg.minecraftSources, cfg.sourcePatches, cfg.rejects)
        patching.applyFeaturePatches(cfg.minecraftSources, cfg.featurePatches)
        assertTrue(cfg.minecraftSources.resolve("net/minecraft/Server.java").readText().contains("void tick() {}"))

        // rebuilding the file patches gives the same patch
        assertEquals(1, patching.rebuildFilePatches(base, cfg.minecraftSources, cfg.sourcePatches, null))
        assertTrue(cfg.sourcePatches.resolve("net/minecraft/Server.java.patch").readText().contains("+    public int ticks; // Paper"))
        assertTrue(cfg.minecraftSources.resolve("net/minecraft/Server.java").readText().contains("void tick() {}"), "back on the feature commits")
    }
}
