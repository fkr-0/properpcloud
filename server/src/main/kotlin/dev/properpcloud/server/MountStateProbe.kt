package dev.properpcloud.server

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

fun interface MountStateProbe {
    fun isOnline(): Boolean

    companion object {
        fun readableDirectory(root: Path): MountStateProbe = MountStateProbe {
            Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(root)
        }

        fun mountedDirectory(root: Path): MountStateProbe = MountStateProbe {
            val absoluteRoot = root.toAbsolutePath().normalize()
            if (!Files.isDirectory(absoluteRoot, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(absoluteRoot)) {
                false
            } else {
                isExactLinuxMountPoint(absoluteRoot) ?: isDistinctFileStoreMount(absoluteRoot)
            }
        }

        private fun isExactLinuxMountPoint(root: Path): Boolean? {
            val mountInfo = Path.of("/proc/self/mountinfo")
            if (!Files.isRegularFile(mountInfo) || !Files.isReadable(mountInfo)) return null
            return runCatching {
                val expected = root.toString()
                Files.newBufferedReader(mountInfo).useLines { lines ->
                    lines.any { line ->
                        val fields = line.split(' ')
                        fields.size > 4 && decodeMountInfoPath(fields[4]) == expected
                    }
                }
            }.getOrNull()
        }

        private fun isDistinctFileStoreMount(root: Path): Boolean {
            val parent = root.parent ?: return false
            return runCatching { Files.getFileStore(root) != Files.getFileStore(parent) }.getOrDefault(false)
        }

        private fun decodeMountInfoPath(value: String): String = buildString(value.length) {
            var index = 0
            while (index < value.length) {
                if (value[index] == '\\' && index + 3 < value.length) {
                    val octal = value.substring(index + 1, index + 4)
                    val decoded = octal.toIntOrNull(8)
                    if (decoded != null) {
                        append(decoded.toChar())
                        index += 4
                        continue
                    }
                }
                append(value[index])
                index += 1
            }
        }
    }
}
