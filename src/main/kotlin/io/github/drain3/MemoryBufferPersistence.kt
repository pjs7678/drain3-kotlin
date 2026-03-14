// SPDX-License-Identifier: MIT

package io.github.drain3

class MemoryBufferPersistence : PersistenceHandler {

    var state: ByteArray? = null

    override fun saveState(state: ByteArray) {
        this.state = state
    }

    override fun loadState(): ByteArray? = state
}
