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

import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream

class PaperweightException(message: String) : RuntimeException(message)

/** Downloads, honouring the https.proxyHost properties or HTTPS_PROXY, as jpm does. */
object Net {
    val offline: Boolean = System.getenv("JPM_OFFLINE").let { it != null && it.isNotBlank() && it != "0" }

    private val client: HttpClient by lazy {
        val builder = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
        if (System.getProperty("https.proxyHost") == null) {
            val env = System.getenv("HTTPS_PROXY") ?: System.getenv("https_proxy")
            if (!env.isNullOrBlank()) {
                val uri = URI.create(if (env.contains("://")) env else "http://$env")
                builder.proxy(ProxySelector.of(InetSocketAddress(uri.host, if (uri.port > 0) uri.port else 80)))
            }
        } else {
            builder.proxy(ProxySelector.getDefault())
        }
        builder.build()
    }

    /** The body of a URL, or null for 404/410. */
    fun getString(url: String): String? {
        if (url.startsWith("file:")) return Path.of(URI.create(url)).let { if (it.exists()) Files.readString(it) else null }
        if (offline) throw PaperweightException("Offline: cannot download $url")
        val res = client.send(request(url), HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() == 404 || res.statusCode() == 410) return null
        if (res.statusCode() != 200) throw PaperweightException("GET $url failed with HTTP ${res.statusCode()}")
        return res.body()
    }

    /** Downloads to [target] (atomically) and checks [expected] (algorithm to hex digest) if given; false for 404/410. */
    fun download(url: String, target: Path, expected: Pair<String, String>? = null): Boolean {
        if (offline && !url.startsWith("file:")) throw PaperweightException("Offline: cannot download $url")
        target.parent.createDirectories()
        val tmp = Files.createTempFile(target.parent, target.fileName.toString(), ".part")
        try {
            if (url.startsWith("file:")) {
                val source = Path.of(URI.create(url))
                if (!source.exists()) return false
                Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING)
            } else {
                val res = client.send(request(url), HttpResponse.BodyHandlers.ofFile(tmp))
                if (res.statusCode() == 404 || res.statusCode() == 410) return false
                if (res.statusCode() != 200) throw PaperweightException("GET $url failed with HTTP ${res.statusCode()}")
            }
            if (expected != null) {
                val actual = digest(tmp, expected.first)
                if (!actual.equals(expected.second, ignoreCase = true)) {
                    throw PaperweightException("Checksum mismatch for $url:\n  expected ${expected.second}\n  got      $actual")
                }
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            return true
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun request(url: String): HttpRequest = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofMinutes(10))
        .header("User-Agent", "jpm-paperweight")
        .GET()
        .build()

    fun digest(file: Path, algorithm: String): String {
        val md = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256(file: Path): String = digest(file, "SHA-256")
}

/** Maven repositories, asked in order. */
class Repositories(val urls: List<String>) {
    fun path(group: String, artifact: String, version: String, file: String): String =
        "${group.replace('.', '/')}/$artifact/${version.encodeVersion()}/${file.encodeVersion()}"

    /** Fetches a file of an artifact into [target], from the first repository that has it; false if none does. */
    fun fetch(group: String, artifact: String, version: String, file: String, target: Path): Boolean =
        ask(group, artifact, version, file) { Net.download(it, target).takeIf { ok -> ok } } ?: false

    fun getString(group: String, artifact: String, version: String, file: String): String? =
        ask(group, artifact, version, file) { Net.getString(it) }

    /**
     * Asks each repository in turn. One that fails (rate limits, outages) is skipped; when none has the file and one
     * failed, that failure is reported rather than "not found".
     */
    private fun <T> ask(group: String, artifact: String, version: String, file: String, get: (String) -> T?): T? {
        var failure: PaperweightException? = null
        for (url in urls) {
            try {
                get(url.trimEnd('/') + "/" + path(group, artifact, version, file))?.let { return it }
            } catch (e: PaperweightException) {
                failure = e
            } catch (e: java.io.IOException) {
                failure = PaperweightException("${e.javaClass.simpleName}: ${e.message}")
            }
        }
        if (failure != null) throw failure
        return null
    }

    /** The versions in maven-metadata.xml, oldest first, from the first repository that lists the artifact. */
    fun versions(group: String, artifact: String): List<String> {
        for (url in urls) {
            val xml = Net.getString(url.trimEnd('/') + "/" + group.replace('.', '/') + "/" + artifact + "/maven-metadata.xml") ?: continue
            return Regex("<version>([^<]+)</version>").findAll(xml).map { it.groupValues[1].trim() }.toList()
        }
        return emptyList()
    }
}

/** Versions such as 26.3+build.2 have characters that need escaping in URLs. */
private fun String.encodeVersion(): String = replace("+", "%2B")
