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

import codechicken.diffpatch.cli.DiffOperation
import codechicken.diffpatch.cli.PatchOperation
import codechicken.diffpatch.match.FuzzyLineMatcher
import codechicken.diffpatch.util.PatchMode
import io.papermc.paperweight.jpm.PaperweightException
import io.papermc.paperweight.jpm.log
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.cadixdev.at.AccessChange
import org.cadixdev.at.AccessTransform
import org.cadixdev.at.AccessTransformSet
import org.cadixdev.at.ModifierChange
import org.cadixdev.at.io.AccessTransformFormats
import org.cadixdev.bombe.type.signature.MethodSignature

/** paperweight's patch workflow for one set of patches (Paper's): applying them onto the base, and rebuilding them. */
class Patching(private val cfg: CoreConfig, private val setup: Setup) {

    // ------------------------------------------------------------------ apply

    /**
     * File patches onto a clone of [base] in [output]: commit "paper File Patches", tagged `file` (and `base` before).
     * Hunks that do not apply go to the rejects folder (sources only) and fail the step.
     */
    fun applyFilePatches(base: Path, output: Path, patches: Path, rejects: Path?, identifier: String = "paper"): Int {
        Git.checkForGit()
        recreateClone(output)
        val git = Git(output)
        checkoutRepoFromUpstream(git, base, "main", "upstream", "main")
        val hook = output.resolve(".git/hooks/post-rewrite")
        hook.parent.createDirectories()
        hook.writeText(javaClass.getResource("/post-rewrite.sh")!!.readText())
        hook.toFile().setExecutable(true)
        git("tag", "-f", "base").execute()
        val applied = when {
            !patches.isDirectory() -> 0
            cfg.gitFilePatches -> {
                val files = patches.filesMatchingRecursive("*.patch").map { output.relativize(it).toString() }
                files.chunked(12).forEach { git("apply", "--3way", *it.toTypedArray()).execute() }
                files.size
            }
            else -> {
                val builder = PatchOperation.builder()
                    .logTo(PrintStream(System.err))
                    .basePath(output)
                    .patchesPath(patches)
                    .outputPath(output)
                    .level(codechicken.diffpatch.util.LogLevel.WARN)
                    .mode(PatchMode.OFFSET)
                    .minFuzz(FuzzyLineMatcher.DEFAULT_MIN_MATCH_SCORE)
                    .lineEnding("\n")
                    .ignorePrefix(".git")
                if (rejects != null) builder.rejectsPath(rejects)
                val result = builder.build().operate()
                commitFilePatches(output, identifier)
                if (result.exit != 0) {
                    val s = result.summary
                    val total = s.failedMatches + s.exactMatches + s.accessMatches + s.offsetMatches + s.fuzzyMatches
                    throw PaperweightException("Failed to apply ${s.failedMatches}/$total hunks; the rejects are in $rejects")
                }
                return result.summary.changedFiles
            }
        }
        commitFilePatches(output, identifier)
        return applied
    }

    private fun commitFilePatches(output: Path, identifier: String) {
        val git = Git(output)
        git("add", "--all", ".").execute()
        ident(git, "File")("commit", "--allow-empty", "--no-verify", "-m", "$identifier File Patches").execute()
        git("tag", "-f", "file").execute()
    }

    private fun recreateClone(target: Path) {
        if (target.exists()) {
            if (target.resolve(".git").isDirectory()) {
                val git = Git(target)
                git("clean", "-fxd").run()
                git("reset", "--hard", "HEAD").run()
            } else {
                Files.list(target).use { s -> s.toList() }.forEach { it.deleteRecursive() }
            }
        } else {
            target.createDirectories()
        }
    }

    /** Feature patches (git am) on top of the `file` commit; on failure the repository is left mid-am to resolve. */
    fun applyFeaturePatches(repo: Path, patches: Path) {
        val git = Git(repo)
        if (git("checkout", "main").run() != 0) git("checkout", "-b", "main").run()
        git("reset", "--hard", "file").execute()
        git("gc").run()
        val statusFile = repo.resolve(".git/patch-apply-failed")
        Files.deleteIfExists(statusFile)
        git("am", "--abort").run()
        val files = if (patches.isDirectory()) Files.list(patches).use { s ->
            s.filter { it.name.endsWith(".patch") }.sorted().toList()
        } else emptyList()
        if (files.isEmpty()) {
            log("No feature patches")
            return
        }
        val temp = Files.createTempDirectory(cfg.cache.also { it.createDirectories() }, "am")
        try {
            val mail = temp.resolve("new").also { it.createDirectories() }
            for (p in files) Files.copy(p, mail.resolve(p.fileName))
            val result = git("am", "--3way", "--ignore-whitespace", temp.toAbsolutePath().toString()).capture()
            if (result.exit != 0) {
                statusFile.writeText("1")
                System.err.println(result.out)
                System.err.println("***   Please review above details and finish the apply then")
                System.err.println("***   save the changes with `jpm paperweight rebuild`")
                throw PaperweightException("Failed to apply feature patches")
            }
            log("${files.size} feature patches applied cleanly")
        } finally {
            temp.deleteRecursive()
        }
    }

    /** applyPatches: the base, then the source and resource file patches, then the feature patches. */
    fun applyAll() {
        val sources = setup.setupSources()
        val resources = setup.setupResources()
        val n = applyFilePatches(sources, cfg.minecraftSources, cfg.sourcePatches, cfg.rejects)
        log("Applied $n source patches")
        val r = applyFilePatches(resources, cfg.minecraftResources, cfg.resourcePatches, null)
        log("Applied $r resource patches")
        applyFeaturePatches(cfg.minecraftSources, cfg.featurePatches)
    }

    // ------------------------------------------------------------------ rebuild

    /**
     * The file patches from the `file` commit of [input] against [base]. `// Paper-AT: <access> <member>` comments in
     * changed sources become access transformers in [atFile] and are removed from the sources (and the commit).
     */
    fun rebuildFilePatches(base: Path, input: Path, patchDir: Path, atFile: Path?): Int {
        patchDir.deleteRecursive()
        patchDir.createDirectories()
        val git = Git(input)
        git("stash", "push").execute()
        git("checkout", "file").execute()
        val filesWithNewAts = if (atFile != null) handleAts(base, input, atFile) else emptyList()
        if (filesWithNewAts.isNotEmpty()) {
            git("add", ".").execute()
            git("commit", "--amend", "--no-edit").execute()
        }
        val result = if (cfg.gitFilePatches) {
            val files = git("diff-tree", "--name-only", "--no-commit-id", "-r", "HEAD").text().lines().filter { it.isNotBlank() }
            files.parallelStream().forEach { f ->
                val patch = git(
                    "format-patch", "--diff-algorithm=myers", "--full-index", "--no-signature", "--no-stat", "--no-numbered",
                    "-1", "HEAD", "--stdout", f
                ).text()
                patchDir.resolve("$f.patch").also { it.parent.createDirectories() }.writeText(patch)
            }
            files.size
        } else {
            DiffOperation.builder()
                .logTo(PrintStream(System.err))
                .aPath(base)
                .bPath(input)
                .outputPath(patchDir)
                .autoHeader(true)
                .level(codechicken.diffpatch.util.LogLevel.WARN)
                .lineEnding("\n")
                .ignorePrefix(".git")
                .ignorePrefix("data/minecraft/structure")
                .ignorePrefix("data/.mc")
                .ignorePrefix("assets/.mc")
                .context(3)
                .build()
                .operate()
                .summary.changedFiles
        }
        git("switch", "-").execute()
        if (filesWithNewAts.isNotEmpty()) {
            // the file commit changed under the feature commits: rebase them, dropping the old file commit and
            // removing the AT comments as they come up
            val execs = filesWithNewAts
                .map { "sed -i -e 's|// Paper-AT:.*||g' $it && ((git add $it && git commit --amend --no-edit) || true)" }
                .flatMap { listOf("--exec", it) }
            try {
                git.withEnv(mapOf("GIT_SEQUENCE_EDITOR" to "sed -i -e 0,/pick/{s/pick/drop/}"))(
                    "rebase",
                    "-i",
                    "file",
                    "--strategy-option=theirs",
                    *execs.toTypedArray()
                ).execute()
            } catch (e: PaperweightException) {
                throw PaperweightException("Conflicts while rebasing the feature commits onto the new file commit: ${e.message}")
            }
        }
        git("stash", "pop").run()
        if (Git(patchDir)("rev-parse", "--is-inside-work-tree").run() == 0) Git(patchDir)("add", "-A", ".").run()
        return result
    }

    private fun handleAts(base: Path, input: Path, atFile: Path): List<String> {
        val all = if (atFile.isRegularFile()) AccessTransformFormats.FML.read(atFile) else AccessTransformSet.create()
        val changed = ArrayList<String>()
        for (src in base.filesMatchingRecursive("*.java")) {
            val rel = base.relativize(src).toString().replace('\\', '/')
            val ats = AccessTransformSet.create()
            if (atsFromSource(input.resolve(rel), ats, rel.removeSuffix(".java"))) {
                if (ats.classes.isNotEmpty()) {
                    val one = cfg.cache.resolve("ats/new.at").also { it.parent.createDirectories() }
                    AccessTransformFormats.FML.writeLF(one, ats)
                    setup.applyAts(base.resolve(rel), base.resolve(rel), one, true)
                }
                changed += rel
            }
            all.merge(ats)
        }
        if (changed.isNotEmpty()) {
            AccessTransformFormats.FML.writeLF(
                atFile,
                all,
                "# This file is auto generated, any changes may be overridden!\n# See CONTRIBUTING.md on how to add access transformers."
            )
        }
        return changed
    }

    private fun atsFromSource(source: Path, ats: AccessTransformSet, className: String): Boolean {
        if (!source.isRegularFile()) return false
        val lines = source.readLines()
        if (lines.none { it.contains("// Paper-AT: ") }) return false
        val fixed = lines.map { line ->
            if (!line.contains("// Paper-AT: ")) return@map line
            val (code, at) = line.split("// Paper-AT: ", limit = 2)
            try {
                val parts = at.trim().split(" ")
                val transform = atFromString(parts[0])
                val name = parts[1]
                val cls = ats.getOrCreateClass(className)
                val i = name.indexOf('(')
                if (i ==
                    -1
                ) cls.mergeField(name, transform) else cls.mergeMethod(MethodSignature.of(name.substring(0, i), name.substring(i)), transform)
                log("New AT in $className: $at")
            } catch (e: Exception) {
                throw PaperweightException("Invalid AT '$at' in $className")
            }
            code
        }
        source.writeText(fixed.joinToString("\n", postfix = "\n"))
        return true
    }

    /** Feature patches: format-patch of everything after the `file` commit, leaving unchanged patches as they were. */
    fun rebuildFeaturePatches(repo: Path, patchDir: Path): Int {
        if (repo.resolve(".git/rebase-apply").exists()) {
            log("REBASE DETECTED - PARTIAL SAVE")
            val last = repo.resolve(".git/rebase-apply/last").readText().trim().toInt()
            val next = repo.resolve(".git/rebase-apply/next").readText().trim().toInt()
            val ordered = Files.list(patchDir).use { s -> s.filter { it.name.endsWith(".patch") }.sorted().toList() }
            for (i in 1..last) if (i < next) Files.deleteIfExists(ordered[i])
        } else {
            patchDir.deleteRecursive()
            patchDir.createDirectories()
        }
        val git = Git(repo)
        git("fetch", "--all", "--prune", "--no-prune-tags").run()
        git(
            "format-patch", "--diff-algorithm=myers", "--zero-commit", "--full-index", "--no-signature", "--no-stat", "-N",
            "-o", patchDir.toAbsolutePath().toString(), "file"
        ).execute()
        val patches = Files.list(patchDir).use { s -> s.filter { it.name.endsWith(".patch") }.sorted().toList() }
        val patchGit = Git(patchDir)
        if (patchGit("rev-parse", "--is-inside-work-tree").run() != 0) return patches.size
        patchGit("add", "-A", ".").execute()
        if (!cfg.filterPatches) return patches.size
        // a patch whose only change is its index line has not changed: keep the committed one
        val unchanged = ConcurrentLinkedQueue<Path>()
        patches.parallelStream().forEach { p ->
            val same = patchGit("diff", "--diff-algorithm=myers", "--staged", p.name).text().lineSequence()
                .filter { it.startsWith('+') || it.startsWith('-') }
                .filterNot { it.startsWith("+++") || it.startsWith("---") }
                .all { it.startsWith("+index") || it.startsWith("-index") }
            if (same) unchanged.add(p)
        }
        for (chunk in unchanged.toList().chunked(50)) {
            patchGit("reset", "HEAD", *chunk.map { it.name }.toTypedArray()).execute()
            patchGit("checkout", "--", *chunk.map { it.name }.toTypedArray()).execute()
        }
        return patches.size - unchanged.size
    }

    /** rebuildPatches: source and resource file patches, then feature patches. */
    fun rebuildAll() {
        val s = rebuildFilePatches(setup.baseSources, cfg.minecraftSources, cfg.sourcePatches, cfg.additionalAts)
        log("Rebuilt $s source patches")
        val r = rebuildFilePatches(setup.baseResources, cfg.minecraftResources, cfg.resourcePatches, null)
        log("Rebuilt $r resource patches")
        val f = rebuildFeaturePatches(cfg.minecraftSources, cfg.featurePatches)
        log("Saved $f modified feature patches")
    }
}

/** An access change in AT syntax: public, protected-f, private+f, -f, ... (as paperweight-lib reads it). */
fun atFromString(input: String): AccessTransform {
    var last = input.length - 1
    val final = if (input[last] == 'f') {
        if (input[--last] == '-') ModifierChange.REMOVE else ModifierChange.ADD
    } else {
        ModifierChange.NONE
    }
    val access = when (input.split("+", "-").first()) {
        "public" -> AccessChange.PUBLIC
        "protected" -> AccessChange.PROTECTED
        "private" -> AccessChange.PRIVATE
        else -> AccessChange.NONE
    }
    return AccessTransform.of(access, final)
}
