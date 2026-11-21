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

import io.papermc.paperweight.jpm.PaperweightException
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.io.path.exists

/** The git command line in a repository, as paperweight's Gradle tasks called it. */
class Git(private val repo: Path, private val env: Map<String, String> = emptyMap()) {
    init {
        if (!repo.exists()) throw PaperweightException("Git directory does not exist: $repo")
    }

    fun withEnv(env: Map<String, String>): Git = Git(repo, env)

    operator fun invoke(vararg args: String): Command {
        val cmd = listOf("git", "-c", "commit.gpgsign=false", "-c", "tag.gpgsign=false", "-c", "core.safecrlf=false", *args)
        val builder = ProcessBuilder(cmd).directory(repo.toFile())
        builder.environment().putAll(env)
        return Command(builder, cmd.joinToString(" "))
    }

    fun disableAutoGpgSigningInRepo() {
        invoke("config", "commit.gpgSign", "false").execute(quiet = true)
        invoke("config", "tag.gpgSign", "false").execute(quiet = true)
    }

    companion object {
        fun checkForGit() {
            val ok = runCatching {
                ProcessBuilder("git", "--version").redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor() ==
                    0
            }
                .getOrDefault(false)
            if (!ok) throw PaperweightException("paperweight needs git installed and on PATH")
        }
    }
}

class Command(private val builder: ProcessBuilder, private val text: String) {
    class Result(val exit: Int, val out: String)

    /** Runs, capturing stdout and stderr together. */
    fun capture(): Result {
        builder.redirectErrorStream(true)
        val p = builder.start()
        val out = p.inputStream.readBytes()
        return Result(p.waitFor(), String(out, Charsets.UTF_8))
    }

    /** Runs, returning the exit code; output is shown unless [quiet]. */
    fun run(quiet: Boolean = true): Int {
        if (debug) System.err.println("$ (${builder.directory()}) $text")
        if (!quiet || debug) {
            // our stdout is for answers to jpm, so the command's output goes to stderr
            builder.redirectErrorStream(true)
            val p = builder.start()
            p.inputStream.copyTo(System.err)
            return p.waitFor()
        }
        return capture().exit
    }

    fun execute(quiet: Boolean = true) {
        val r = if (quiet && !debug) capture() else Result(run(quiet), "")
        if (r.exit != 0) {
            if (r.out.isNotBlank()) System.err.println(r.out.trimEnd())
            throw PaperweightException("Command failed with exit code ${r.exit}: $text")
        }
    }

    /** stdout, failing on a non-zero exit. */
    fun text(): String {
        builder.redirectError(ProcessBuilder.Redirect.INHERIT)
        val p = builder.start()
        val out = ByteArrayOutputStream()
        p.inputStream.copyTo(out)
        val exit = p.waitFor()
        if (exit != 0) throw PaperweightException("Command failed with exit code $exit: $text")
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private companion object {
        val debug = System.getenv("PAPERWEIGHT_DEBUG").let { it != null && it != "0" && it.isNotBlank() }
    }
}

/** Makes [git]'s repository a clone of [upstream] at [upstreamBranch] (a ref of upstream when [ref]), on [branchName]. */
fun checkoutRepoFromUpstream(
    git: Git,
    upstream: Path,
    upstreamBranch: String,
    upstreamName: String = "upstream",
    branchName: String = "master",
    ref: Boolean = false,
) {
    git("init", "--quiet").execute()
    git.disableAutoGpgSigningInRepo()
    git("remote", "remove", upstreamName).run()
    git("remote", "add", upstreamName, upstream.toUri().toString()).execute()
    git("fetch", upstreamName, "--prune", "--prune-tags", "--force").execute()
    if (git("checkout", branchName).run() != 0) {
        git("checkout", "-b", branchName).run()
    }
    git("reset", "--hard", if (ref) upstreamBranch else "$upstreamName/$upstreamBranch").execute()
    git("gc").run()
}

fun Path.deleteRecursive() {
    if (!Files.exists(this)) return
    Files.walk(this).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
}

fun Path.copyRecursivelyTo(target: Path) {
    Files.walk(this).use { s ->
        s.forEach { src ->
            val dest = target.resolve(relativize(src).toString())
            if (Files.isDirectory(src)) {
                Files.createDirectories(dest)
            } else {
                Files.createDirectories(dest.parent)
                Files.copy(src, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}

fun Path.filesMatchingRecursive(glob: String): List<Path> {
    if (!Files.isDirectory(this)) return emptyList()
    val matcher = fileSystem.getPathMatcher("glob:$glob")
    return Files.walk(this).use { s -> s.filter { Files.isRegularFile(it) && matcher.matches(it.fileName) }.sorted().toList() }
}
