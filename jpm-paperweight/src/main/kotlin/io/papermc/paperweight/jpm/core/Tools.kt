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
import io.papermc.paperweight.jpm.Repositories
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

/** A Maven artifact as mache.json names its tools. */
data class MavenArtifact(
    val group: String,
    val name: String,
    val version: String,
    val classifier: String? = null,
    val extension: String? = null,
) {
    val fileName: String get() = "$name-$version" + (classifier?.let { "-$it" } ?: "") + "." + (extension ?: "jar")

    companion object {
        /** group:name:version[:classifier][@extension] */
        fun parse(coords: String): MavenArtifact {
            val ext = coords.substringAfter('@', "").ifEmpty { null }
            val p = coords.substringBefore('@').split(':')
            if (p.size < 3) throw PaperweightException("Not Maven coordinates: $coords")
            return MavenArtifact(p[0], p[1], p[2], p.getOrNull(3), ext)
        }
    }
}

/**
 * Fetches one artifact (no dependencies: mache's tools are self-contained jars) into [cache], as
 * `<cache>/<group>/<name>/<version>/<file>`. `-SNAPSHOT` versions are looked up in their maven-metadata.xml.
 */
fun Repositories.artifact(a: MavenArtifact, cache: Path): Path {
    val target = cache.resolve(a.group).resolve(a.name).resolve(a.version).resolve(a.fileName)
    if (target.exists()) return target
    target.parent.createDirectories()
    if (a.version.endsWith("-SNAPSHOT")) {
        val meta = getString(a.group, a.name, a.version, "maven-metadata.xml")
        val ext = a.extension ?: "jar"
        val value = meta?.let { snapshotValue(it, a.classifier, ext) }
        if (value != null) {
            val file = "${a.name}-$value" + (a.classifier?.let { "-$it" } ?: "") + ".$ext"
            if (fetch(a.group, a.name, a.version, file, target)) return target
        }
    }
    if (!fetch(a.group, a.name, a.version, a.fileName, target)) {
        // an artifact that is not a jar says so in its POM (mache's unpick definitions are a zip)
        if (a.extension == null) {
            val packaging = getString(a.group, a.name, a.version, "${a.name}-${a.version}.pom")
                ?.let { Regex("<packaging>\\s*([^<\\s]+)\\s*</packaging>").find(it)?.groupValues?.get(1) }
            if (packaging != null && packaging != "jar" && packaging != "pom") return artifact(a.copy(extension = packaging), cache)
        }
        throw PaperweightException("Could not find ${a.group}:${a.name}:${a.version}${a.classifier?.let { ":$it" } ?: ""} in $urls")
    }
    return target
}

/** The timestamped version of a snapshot's file with this classifier and extension. */
fun snapshotValue(metadata: String, classifier: String?, extension: String): String? {
    for (m in Regex("<snapshotVersion>(.*?)</snapshotVersion>", RegexOption.DOT_MATCHES_ALL).findAll(metadata)) {
        val body = m.groupValues[1]
        val c = Regex("<classifier>(.*?)</classifier>").find(body)?.groupValues?.get(1)
        val e = Regex("<extension>(.*?)</extension>").find(body)?.groupValues?.get(1)
        if (c == classifier && e == extension) return Regex("<value>(.*?)</value>").find(body)?.groupValues?.get(1)
    }
    return null
}

/** Runs a tool jar's main class (from its manifest) on [java], logging to [logFile]; fails with the log's tail. */
fun runJar(javaExe: Path, classpath: List<Path>, workDir: Path, logFile: Path, jvmArgs: List<String>, args: List<String>) {
    val main = classpath.firstNotNullOfOrNull { j -> JarFile(j.toFile()).use { it.manifest?.mainAttributes?.getValue("Main-Class") } }
        ?: throw PaperweightException("No Main-Class in $classpath")
    workDir.createDirectories()
    logFile.parent.createDirectories()
    val cmd = listOf(javaExe.toString()) + jvmArgs + listOf("-classpath", classpath.joinToString(java.io.File.pathSeparator), main) + args
    Files.writeString(logFile, "Command: ${cmd.joinToString(" ")}\n")
    val p = ProcessBuilder(cmd).directory(workDir.toFile())
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
        .start()
    val exit = p.waitFor()
    if (exit != 0) {
        val tail = Files.readAllLines(logFile).takeLast(30).joinToString("\n")
        throw PaperweightException("${classpath.first().fileName} failed with exit code $exit; log: $logFile\n$tail")
    }
}

/** The java of the JDK running this extension, or JAVA_HOME's. */
fun javaExecutable(): Path {
    val home = System.getenv("PAPERWEIGHT_JAVA_HOME") ?: System.getProperty("java.home")
    val exe = if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"
    return Path.of(home, "bin", exe)
}

/** Writes access transformers sorted, with LF line endings and an optional header, as paperweight-lib's writeLF does. */
fun org.cadixdev.at.io.AccessTransformFormat.writeLF(path: Path, at: org.cadixdev.at.AccessTransformSet, header: String? = null) {
    val sw = java.io.StringWriter()
    java.io.BufferedWriter(sw).use { write(it, at) }
    val lines = ArrayList<String>()
    if (header != null) lines.add(header)
    lines.addAll(sw.toString().replace("\r\n", "\n").split("\n").filter { it.isNotBlank() }.sorted())
    path.parent?.createDirectories()
    Files.writeString(path, lines.joinToString("\n", postfix = "\n"))
}
