package dev.properpcloud.desktop.security

import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class SecretServiceVault(private val executable: String = "secret-tool") {
    fun available(): Boolean = runCatching {
        val process = ProcessBuilder(executable, "--help")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        waitFor(process, 3, "Secret Service availability check") == 0
    }.getOrDefault(false)

    fun store(key: String, secret: CharArray) {
        validateKey(key)
        var process: Process? = null
        try {
            process = ProcessBuilder(executable, "store", "--label=properpcloud", "service", "properpcloud", "key", key)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            process.outputStream.writer(StandardCharsets.UTF_8).use { writer ->
                writer.write(secret)
                writer.write("\n")
            }
            check(waitFor(process, 15, "Secret Service store") == 0) { "Secret Service rejected the credential" }
        } finally {
            secret.fill('\u0000')
            process?.let(::terminateIfAlive)
        }
    }

    fun lookup(key: String): CharArray? {
        validateKey(key)
        val process = ProcessBuilder(executable, "lookup", "service", "properpcloud", "key", key)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val output = CompletableFuture.supplyAsync {
            process.inputStream.readNBytes(MAX_SECRET_BYTES + 1)
        }
        try {
            val exitCode = waitFor(process, 10, "Secret Service lookup")
            if (exitCode != 0) return null
            val bytes = output.get(2, TimeUnit.SECONDS)
            try {
                check(bytes.size <= MAX_SECRET_BYTES) { "Secret Service returned an oversized credential" }
                return String(bytes, StandardCharsets.UTF_8).trimEnd().toCharArray().takeIf { it.isNotEmpty() }
            } finally {
                bytes.fill(0)
            }
        } finally {
            output.cancel(true)
            terminateIfAlive(process)
        }
    }

    fun clear(key: String) {
        validateKey(key)
        val process = ProcessBuilder(executable, "clear", "service", "properpcloud", "key", key)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try {
            val exitCode = waitFor(process, 10, "Secret Service clear")
            check(exitCode == 0 || exitCode == 1) { "Secret Service clear failed" }
        } finally {
            terminateIfAlive(process)
        }
    }

    private fun validateKey(key: String) {
        require(KEY_PATTERN.matches(key)) { "invalid Secret Service key" }
    }

    private fun waitFor(process: Process, timeoutSeconds: Long, operation: String): Int {
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            terminateIfAlive(process)
            error("$operation timed out")
        }
        return process.exitValue()
    }

    private fun terminateIfAlive(process: Process) {
        if (!process.isAlive) return
        process.destroy()
        if (!process.waitFor(1, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(1, TimeUnit.SECONDS)
        }
    }

    private companion object {
        const val MAX_SECRET_BYTES = 64 * 1_024
        val KEY_PATTERN = Regex("[A-Za-z0-9._-]{1,128}")
    }
}
