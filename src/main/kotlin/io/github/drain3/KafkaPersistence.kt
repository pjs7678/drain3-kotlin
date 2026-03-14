// SPDX-License-Identifier: MIT

package io.github.drain3

import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import java.time.Duration
import java.util.Properties

class KafkaPersistence(
    private val topic: String,
    private val snapshotPollTimeoutSec: Long = 60,
    private val producerProperties: Properties,
    private val consumerProperties: Properties
) : PersistenceHandler {

    private val producer: KafkaProducer<ByteArray, ByteArray> = KafkaProducer(producerProperties)

    override fun saveState(state: ByteArray) {
        producer.send(ProducerRecord(topic, state))
    }

    override fun loadState(): ByteArray? {
        val consumer = KafkaConsumer<ByteArray, ByteArray>(consumerProperties)
        val partition = TopicPartition(topic, 0)
        consumer.assign(listOf(partition))

        return try {
            val endOffsets = consumer.endOffsets(listOf(partition))
            val endOffset = endOffsets.values.first()
            if (endOffset > 0) {
                consumer.seek(partition, endOffset - 1)
                val records = consumer.poll(Duration.ofSeconds(snapshotPollTimeoutSec))
                if (records.isEmpty) {
                    throw RuntimeException(
                        "No message received from Kafka during restore even though end_offset>0"
                    )
                }
                records.records(partition).first().value()
            } else {
                null
            }
        } finally {
            consumer.close()
        }
    }
}
