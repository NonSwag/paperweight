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
import io.papermc.paperweight.jpm.PaperweightException
import java.nio.file.Path

/**
 * Reads [CoreConfig] from `jpm info --json --classpath`: the workspace module whose `"extensions"` has a `"paperweight"`
 * object with a `"minecraftVersion"` is the server.
 *
 * ```jsonc
 * "extensions": {
 *   "paperweight": {
 *     "minecraftVersion": "26.3",
 *     "mache": "io.papermc:mache:26.3+build.2",
 *     // optional, relative to the module; these are the defaults:
 *     "patches": "patches",                 // sources/, resources/, features/, rejected/
 *     "buildData": "../build-data",          // paper.at, dev-imports.txt
 *     "gitFilePatches": false,
 *     "filterPatches": true,
 *     "macheRepository": "https://repo.papermc.io/repository/maven-public/"
 *   }
 * }
 * ```
 */
object ConfigLoader {
    fun load(): CoreConfig {
        val jpm = System.getenv("JPM_EXE") ?: "jpm"
        val dir = System.getenv("JPM_MODULE_DIR") ?: System.getenv("JPM_PROJECT_DIR") ?: "."
        val p = ProcessBuilder(jpm, "info", "--json", "--classpath").directory(Path.of(dir).toFile())
            .redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        if (p.waitFor() != 0) throw PaperweightException("`jpm info --json --classpath` failed")
        return parse(Gson().fromJson(out, JsonObject::class.java))
    }

    fun parse(info: JsonObject): CoreConfig {
        val root = Path.of(info["projectDir"].asString)
        val modules = info.getAsJsonArray("modules")?.map { it.asJsonObject } ?: emptyList()
        val server = modules.firstOrNull { it.settings() != null }
            ?: throw PaperweightException(
                "No module of this project has \"extensions\": { \"paperweight\": { \"minecraftVersion\": ..., \"mache\": ... } }"
            )
        val s = server.settings()!!
        val dir = Path.of(server["dir"].asString)
        fun str(name: String) = s[name]?.takeIf { it.isJsonPrimitive }?.asString
        val patches = dir.resolve(str("patches") ?: "patches").normalize()
        val buildData = dir.resolve(str("buildData") ?: "../build-data").normalize()
        val runtime = server.getAsJsonObject("classpaths")?.getAsJsonArray("runtime")?.mapNotNull { pkg ->
            val o = pkg.asJsonObject
            val g = o["group"]?.asString
            val a = o["artifact"]?.asString
            val v = o["version"]?.asString
            if (g != null && a != null && v != null && o["kind"]?.asString in setOf("maven", "patched")) "$g:$a:$v" else null
        } ?: emptyList()
        return CoreConfig(
            root = root,
            serverDir = dir,
            minecraftVersion = str("minecraftVersion")!!,
            mache = MavenArtifact.parse(
                str("mache") ?: throw PaperweightException("extensions.paperweight needs \"mache\", e.g. io.papermc:mache:26.3+build.2")
            ),
            macheRepo = str("macheRepository") ?: PAPER_MAVEN_REPO_URL,
            sourcePatches = patches.resolve("sources"),
            resourcePatches = patches.resolve("resources"),
            featurePatches = patches.resolve("features"),
            rejects = patches.resolve("rejected"),
            additionalAts = buildData.resolve("paper.at"),
            devImports = buildData.resolve("dev-imports.txt"),
            gitFilePatches = s["gitFilePatches"]?.asBoolean ?: false,
            filterPatches = s["filterPatches"]?.asBoolean ?: true,
            runtimeLibraries = runtime,
        )
    }

    private fun JsonObject.settings(): JsonObject? =
        getAsJsonObject("extensions")?.getAsJsonObject("paperweight")?.takeIf { it.has("minecraftVersion") }
}
