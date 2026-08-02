package dev.properpcloud.desktop.security

import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class SecretServiceVault(private val executable: String = "secret-tool") {
    fun available(): Boolean = runCatching {
        ProcessBuilder(executable, "--help").redirectErrorStream(true).start().also { it.waitFor(3, TimeUnit.SECONDS) }.exitValue() == 0
    }.getOrDefault(false)

    fun store(key: String, secret: CharArray) {
        val process = ProcessBuilder(executable, "store", "--label=properpcloud", "service", "properpcloud", "key", key)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process.outputStream.writer(StandardCharsets.UTF_8).use { writer -> writer.write(secret); writer.write("\n") }
        secret.fill('\u0000')
        check(process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() == 0) { "Secret Service rejected the credential" }
    }

    fun lookup(key: String): CharArray? {
        val process = ProcessBuilder(executable, "lookup", "service", "properpcloud", "key", key)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        check(process.waitFor(10, TimeUnit.SECONDS)) { "Secret Service lookup timed out" }
        if (process.exitValue() != 0) return null
        return process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText().trimEnd().toCharArray() }.takeIf { it.isNotEmpty() }
    }

    fun clear(key: String) {
        val process = ProcessBuilder(executable, "clear", "service", "properpcloud", "key", key)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        check(process.waitFor(10, TimeUnit.SECONDS)) { "Secret Service clear timed out" }
    }
}
