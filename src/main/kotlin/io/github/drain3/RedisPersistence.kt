// SPDX-License-Identifier: MIT

package io.github.drain3

import redis.clients.jedis.Jedis
import redis.clients.jedis.DefaultJedisClientConfig

class RedisPersistence(
    private val redisHost: String,
    private val redisPort: Int,
    private val redisDb: Int,
    private val redisPass: String?,
    private val isSsl: Boolean,
    private val redisKey: String
) : PersistenceHandler {

    private val jedis: Jedis

    init {
        val config = DefaultJedisClientConfig.builder()
            .database(redisDb)
            .password(redisPass)
            .ssl(isSsl)
            .build()
        jedis = Jedis(redisHost, redisPort, config)
    }

    override fun saveState(state: ByteArray) {
        jedis.set(redisKey.toByteArray(), state)
    }

    override fun loadState(): ByteArray? {
        return jedis.get(redisKey.toByteArray())
    }
}
