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

package io.papermc.paperweight.jpm

import com.google.gson.GsonBuilder
import java.nio.file.Path
import kotlin.system.exitProcess

const val USAGE = """jpm paperweight: Paper's build tooling for jpm

Plugins against server internals (paperweight-userdev):
  jpm paperweight init [version]       add the Paper server to this project ("dev": ext:paperweight/server)
  jpm paperweight server [version]     set up a dev bundle and print where its server jar is
  jpm paperweight resolve server <v>   what jpm calls to resolve ext:paperweight/server (JSON on stdout)

Paper itself (paperweight-core), in a project whose server module has "extensions": { "paperweight": ... }:
  jpm paperweight apply                set up Minecraft's sources and apply every patch (applyPatches)
  jpm paperweight apply-files          ... the source and resource file patches only
  jpm paperweight apply-features       ... the feature patches only, onto the applied file patches
  jpm paperweight rebuild              rebuild every patch from src/minecraft (rebuildPatches)
  jpm paperweight rebuild-files        ... the file patches only
  jpm paperweight rebuild-features     ... the feature patches only
  "ext:paperweight/mache": "<mache version>" in the server's dependencies is Minecraft's libraries

A version is a dev bundle version (26.3.build.257-stable), a Minecraft version for its newest
stable build (26.3), or latest. JPM_PAPERWEIGHT_REPOSITORY replaces the Paper repository."""

fun main(args: Array<String>) {
    try {
        exitProcess(run(args.toList()))
    } catch (e: PaperweightException) {
        System.err.println("error: ${e.message}")
        exitProcess(1)
    }
}

fun run(args: List<String>): Int {
    val home = Path.of(System.getenv("JPM_HOME") ?: (System.getProperty("user.home") + "/.jpm")).resolve("paperweight")
    val paper = System.getenv("JPM_PAPERWEIGHT_REPOSITORY")?.takeIf { it.isNotBlank() } ?: Userdev.PAPER_REPOSITORY
    val userdev = Userdev(home, Repositories(listOf(paper)))
    when (args.firstOrNull()) {
        "resolve" -> {
            val resolved = when (args.getOrNull(1)) {
                "server" -> userdev.resolve(args.getOrElse(2) { "latest" })
                "mache" -> userdev.macheLibraries(args.getOrNull(2) ?: throw PaperweightException("ext:paperweight/mache needs a mache version"))
                else -> {
                    System.err.println("jpm-paperweight provides ext:paperweight/server and ext:paperweight/mache")
                    return 2
                }
            }
            println(GsonBuilder().disableHtmlEscaping().create().toJson(resolved))
            return 0
        }
        "server" -> {
            val resolved = userdev.resolve(args.getOrElse(1) { "latest" })
            println(resolved.jars.first())
            return 0
        }
        "init" -> {
            val version = userdev.version(args.getOrElse(1) { "latest" })
            val jpm = System.getenv("JPM_EXE") ?: "jpm"
            // paper-api and the server's libraries come from the Paper repository
            val cmd = listOf(jpm, "add", "--dev", "-r", paper, "ext:paperweight/server@$version")
            return ProcessBuilder(cmd).inheritIO().start().waitFor()
        }
        "apply", "apply-files", "apply-features", "rebuild", "rebuild-files", "rebuild-features" -> {
            val cfg = io.papermc.paperweight.jpm.core.ConfigLoader.load()
            val setup = io.papermc.paperweight.jpm.core.Setup(cfg)
            val patching = io.papermc.paperweight.jpm.core.Patching(cfg, setup)
            when (args.first()) {
                "apply" -> patching.applyAll()
                "apply-files" -> {
                    patching.applyFilePatches(setup.setupSources(), cfg.minecraftSources, cfg.sourcePatches, cfg.rejects)
                    patching.applyFilePatches(setup.setupResources(), cfg.minecraftResources, cfg.resourcePatches, null)
                }
                "apply-features" -> patching.applyFeaturePatches(cfg.minecraftSources, cfg.featurePatches)
                "rebuild" -> patching.rebuildAll()
                "rebuild-files" -> {
                    patching.rebuildFilePatches(setup.baseSources, cfg.minecraftSources, cfg.sourcePatches, cfg.additionalAts)
                    patching.rebuildFilePatches(setup.baseResources, cfg.minecraftResources, cfg.resourcePatches, null)
                }
                "rebuild-features" -> patching.rebuildFeaturePatches(cfg.minecraftSources, cfg.featurePatches)
            }
            return 0
        }
        null, "help", "-h", "--help" -> {
            println(USAGE)
            return if (args.isEmpty()) 2 else 0
        }
        else -> {
            System.err.println("Unknown command ${args.first()}\n\n$USAGE")
            return 2
        }
    }
}
