package dev.properpcloud.desktop.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.io.path.writeText

class SecretServiceVaultTest {
    @Test
    fun `missing secret tool is unavailable`() {
        assertFalse(SecretServiceVault("definitely-not-a-real-secret-tool").available())
    }

    @Test
    fun `store clears caller buffer after successful handoff`() {
        val root = Files.createTempDirectory("properpcloud-secret-tool-test-")
        try {
            val captured = root.resolve("captured.txt")
            val executable = root.resolve("secret-tool")
            executable.writeText(
                """#!/bin/sh
                |set -eu
                |if [ "${'$'}{1:-}" = "--help" ]; then exit 0; fi
                |if [ "${'$'}{1:-}" = "store" ]; then cat > '${captured.toAbsolutePath()}'; exit 0; fi
                |exit 1
                |""".trimMargin(),
            )
            executable.toFile().setExecutable(true)
            val secret = "temporary-token".toCharArray()

            SecretServiceVault(executable.toString()).store("pcloud-session", secret)

            assertArrayEquals(CharArray(secret.size), secret)
            assertTrue(captured.toFile().readText() == "temporary-token\n")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `invalid lookup key is rejected before process launch`() {
        val failure = runCatching { SecretServiceVault("unused").lookup("bad key") }
        assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `oversized lookup output is rejected`() {
        val root = Files.createTempDirectory("properpcloud-secret-tool-oversized-")
        try {
            val executable = root.resolve("secret-tool")
            executable.writeText(
                """#!/bin/sh
                |set -eu
                |if [ "${'$'}{1:-}" = "lookup" ]; then
                |  python3 -c 'import sys; sys.stdout.write("x" * 65537)'
                |  exit 0
                |fi
                |exit 1
                |""".trimMargin(),
            )
            executable.toFile().setExecutable(true)

            val failure = runCatching { SecretServiceVault(executable.toString()).lookup("pcloud-session") }

            assertTrue(failure.exceptionOrNull()?.message == "Secret Service returned an oversized credential")
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
