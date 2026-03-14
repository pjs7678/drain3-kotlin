// SPDX-License-Identifier: MIT

package io.github.drain3

interface PersistenceHandler {
    fun saveState(state: ByteArray)
    fun loadState(): ByteArray?
}
