// SPDX-License-Identifier: MIT

package io.github.drain3

import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.sync.RedisCommands
import io.lettuce.core.codec.ByteArrayCodec

class RedisPersistence(
    private val redisHost: String,
    private val redisPort: Int,
    private val redisDb: Int,
    private val redisPass: String?,
    private val isSsl: Boolean,
    private val redisKey: String
) : PersistenceHandler {

    private val commands: RedisCommands<ByteArray, ByteArray>

    init {
        val uriBuilder = RedisURI.builder()
            .withHost(redisHost)
            .withPort(redisPort)
            .withDatabase(redisDb)
            .withSsl(isSsl)
        if (redisPass != null) {
            uriBuilder.withPassword(redisPass.toCharArray())
        }
        val client = RedisClient.create(uriBuilder.build())
        val connection = client.connect(ByteArrayCodec.INSTANCE)
        commands = connection.sync()
    }

    override fun saveState(state: ByteArray) {
        commands.set(redisKey.toByteArray(), state)
    }

    override fun loadState(): ByteArray? {
        return commands.get(redisKey.toByteArray())
    }
}
