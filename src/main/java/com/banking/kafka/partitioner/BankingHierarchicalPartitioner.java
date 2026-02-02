package com.banking.kafka.partitioner;

import org.apache.kafka.clients.producer.Partitioner;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Custom Kafka Partitioner for Banking POC.
 *
 * Routes messages to partitions based on institution ID from Kafka headers.
 * Ensures all messages from the same banking institution go to the same partition
 * for consistent ordering and efficient processing.
 *
 * Configuration:
 * - institution.id.header: Name of the header containing institution ID (default: "X-Institution-Id")
 * - default.partition: Partition to use when institution ID is missing (default: 0)
 *
 * Usage in producer config:
 * props.put(ProducerConfig.PARTITIONER_CLASS_CONFIG, BankingHierarchicalPartitioner.class.getName());
 * props.put("institution.id.header", "X-Institution-Id");
 */
public class BankingHierarchicalPartitioner implements Partitioner {

    private static final Logger log = LoggerFactory.getLogger(BankingHierarchicalPartitioner.class);

    private static final String INSTITUTION_ID_HEADER_CONFIG = "institution.id.header";
    private static final String DEFAULT_PARTITION_CONFIG = "default.partition";

    private static final String DEFAULT_INSTITUTION_ID_HEADER = "X-Institution-Id";
    private static final int DEFAULT_PARTITION = 0;

    private String institutionIdHeader;
    private int defaultPartition;

    @Override
    public void configure(Map<String, ?> configs) {
        Object institutionIdHeaderValue = configs.get(INSTITUTION_ID_HEADER_CONFIG);
        this.institutionIdHeader = institutionIdHeaderValue != null
                ? institutionIdHeaderValue.toString()
                : DEFAULT_INSTITUTION_ID_HEADER;

        Object defaultPartitionValue = configs.get(DEFAULT_PARTITION_CONFIG);
        this.defaultPartition = defaultPartitionValue != null
                ? Integer.parseInt(defaultPartitionValue.toString())
                : DEFAULT_PARTITION;

        log.info("BankingHierarchicalPartitioner configured: institutionIdHeader={}, defaultPartition={}",
                institutionIdHeader, defaultPartition);
    }

    @Override
    public int partition(String topic, Object key, byte[] keyBytes, Object value, byte[] valueBytes,
                         Cluster cluster) {
        // This method is called for records without headers (old API)
        // Fall back to default partition
        int numPartitions = cluster.partitionCountForTopic(topic);
        log.debug("No headers available, using default partition {} for topic {}", defaultPartition, topic);
        return Math.min(defaultPartition, numPartitions - 1);
    }

    /**
     * Partition method with headers support (Kafka 2.4+)
     */
    public int partition(String topic, Object key, byte[] keyBytes, Object value, byte[] valueBytes,
                         Cluster cluster, Headers headers) {
        int numPartitions = cluster.partitionCountForTopic(topic);

        if (numPartitions == 0) {
            log.warn("No partitions available for topic {}", topic);
            return 0;
        }

        // Extract institution ID from headers
        String institutionId = extractInstitutionId(headers);

        if (institutionId == null || institutionId.isEmpty()) {
            log.debug("Institution ID not found in headers, using default partition {} for topic {}",
                    defaultPartition, topic);
            return Math.min(defaultPartition, numPartitions - 1);
        }

        // Hash institution ID to determine partition
        // Use consistent hashing to ensure same institution always goes to same partition
        int partition = Math.abs(institutionId.hashCode()) % numPartitions;

        log.debug("Routing message for institution {} to partition {} (topic: {}, total partitions: {})",
                institutionId, partition, topic, numPartitions);

        return partition;
    }

    /**
     * Extract institution ID from Kafka headers
     */
    private String extractInstitutionId(Headers headers) {
        if (headers == null) {
            return null;
        }

        Header institutionHeader = headers.lastHeader(institutionIdHeader);
        if (institutionHeader == null) {
            return null;
        }

        byte[] value = institutionHeader.value();
        if (value == null) {
            return null;
        }

        return new String(value, StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        // No resources to close
        log.debug("BankingHierarchicalPartitioner closed");
    }

    /**
     * Get the configured institution ID header name (for testing)
     */
    public String getInstitutionIdHeader() {
        return institutionIdHeader;
    }

    /**
     * Get the configured default partition (for testing)
     */
    public int getDefaultPartition() {
        return defaultPartition;
    }
}
