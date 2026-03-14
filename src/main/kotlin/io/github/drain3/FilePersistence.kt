// SPDX-License-Identifier: MIT

package io.github.drain3

import java.nio.file.Files
import java.nio.file.Path

class FilePersistence(private val filePath: String) : PersistenceHandler {

    override fun saveState(state: ByteArray) {
        Files.write(Path.of(filePath), state)
    }

    override fun loadState(): ByteArray? {
        val path = Path.of(filePath)
        if (!Files.exists(path)) return null
        return Files.readAllBytes(path)
    }
}
