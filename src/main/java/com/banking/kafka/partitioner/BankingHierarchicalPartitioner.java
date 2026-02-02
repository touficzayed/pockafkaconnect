package com.banking.kafka.partitioner;

import org.apache.kafka.clients.producer.Partitioner;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Custom Kafka Partitioner for Banking POC.
 *
 * Routes messages to partitions based on institution ID from Kafka headers.
 * Supports two modes:
 * <ul>
 *   <li><b>Deterministic mapping</b>: explicit bank-to-partition assignment via a CSV mapping file.
 *       Each line is: bankCode,partitionNumber (e.g., "BNK001,0")</li>
 *   <li><b>Murmur2 hashing</b> (fallback): consistent hashing using Kafka's built-in
 *       murmur2 algorithm, same as DefaultPartitioner</li>
 * </ul>
 *
 * Configuration:
 * - institution.id.header: Header name for institution ID (default: "X-Institution-Id")
 * - default.partition: Partition when institution ID is missing (default: 0)
 * - bank.partition.mapping.file: Path to CSV mapping file (optional)
 */
public class BankingHierarchicalPartitioner implements Partitioner {

    private static final Logger log = LoggerFactory.getLogger(BankingHierarchicalPartitioner.class);

    static final String INSTITUTION_ID_HEADER_CONFIG = "institution.id.header";
    static final String DEFAULT_PARTITION_CONFIG = "default.partition";
    static final String BANK_PARTITION_MAPPING_FILE_CONFIG = "bank.partition.mapping.file";

    private static final String DEFAULT_INSTITUTION_ID_HEADER = "X-Institution-Id";
    private static final int DEFAULT_PARTITION = 0;

    private String institutionIdHeader;
    private int defaultPartition;
    private Map<String, Integer> bankPartitionMapping;

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

        Object mappingFileValue = configs.get(BANK_PARTITION_MAPPING_FILE_CONFIG);
        if (mappingFileValue != null && !mappingFileValue.toString().isEmpty()) {
            this.bankPartitionMapping = loadMappingFile(mappingFileValue.toString());
            log.info("Loaded deterministic partition mapping for {} banks from {}",
                    bankPartitionMapping.size(), mappingFileValue);
        } else {
            this.bankPartitionMapping = Collections.emptyMap();
            log.info("No mapping file configured, using Murmur2 hashing");
        }

        log.info("BankingHierarchicalPartitioner configured: institutionIdHeader={}, defaultPartition={}, mappingMode={}",
                institutionIdHeader, defaultPartition,
                bankPartitionMapping.isEmpty() ? "murmur2" : "deterministic");
    }

    @Override
    public int partition(String topic, Object key, byte[] keyBytes, Object value, byte[] valueBytes,
                         Cluster cluster) {
        int numPartitions = cluster.partitionCountForTopic(topic);
        log.debug("No headers available, using default partition {} for topic {}", defaultPartition, topic);
        return Math.min(defaultPartition, numPartitions - 1);
    }

    /**
     * Partition method with headers support (Kafka 2.4+).
     */
    public int partition(String topic, Object key, byte[] keyBytes, Object value, byte[] valueBytes,
                         Cluster cluster, Headers headers) {
        int numPartitions = cluster.partitionCountForTopic(topic);

        if (numPartitions == 0) {
            log.warn("No partitions available for topic {}", topic);
            return 0;
        }

        String institutionId = extractInstitutionId(headers);

        if (institutionId == null || institutionId.isEmpty()) {
            log.debug("Institution ID not found in headers, using default partition {} for topic {}",
                    defaultPartition, topic);
            return Math.min(defaultPartition, numPartitions - 1);
        }

        int partition;

        // Try deterministic mapping first
        Integer mapped = bankPartitionMapping.get(institutionId);
        if (mapped != null) {
            partition = mapped % numPartitions;
            log.debug("Deterministic mapping: institution {} -> partition {} (topic: {})",
                    institutionId, partition, topic);
        } else {
            // Fall back to Murmur2 hashing (same algorithm as Kafka's DefaultPartitioner)
            partition = toPositive(Utils.murmur2(institutionId.getBytes(StandardCharsets.UTF_8))) % numPartitions;
            log.debug("Murmur2 hashing: institution {} -> partition {} (topic: {}, total: {})",
                    institutionId, partition, topic, numPartitions);
        }

        return partition;
    }

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

    /**
     * Load bank-to-partition mapping from a CSV file.
     * Format: one line per bank, "bankCode,partitionNumber".
     * Lines starting with '#' are comments. Empty lines are ignored.
     */
    static Map<String, Integer> loadMappingFile(String filePath) {
        Map<String, Integer> mapping = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split(",", 2);
                if (parts.length == 2) {
                    String bankCode = parts[0].trim();
                    int partition = Integer.parseInt(parts[1].trim());
                    mapping.put(bankCode, partition);
                } else {
                    log.warn("Ignoring malformed mapping line: {}", line);
                }
            }
        } catch (IOException e) {
            log.error("Failed to load bank partition mapping from {}: {}", filePath, e.getMessage());
        } catch (NumberFormatException e) {
            log.error("Invalid partition number in mapping file {}: {}", filePath, e.getMessage());
        }
        return Collections.unmodifiableMap(mapping);
    }

    static int toPositive(int number) {
        return number & 0x7fffffff;
    }

    @Override
    public void close() {
        log.debug("BankingHierarchicalPartitioner closed");
    }

    public String getInstitutionIdHeader() {
        return institutionIdHeader;
    }

    public int getDefaultPartition() {
        return defaultPartition;
    }

    /**
     * Get the current bank-to-partition mapping (for testing).
     */
    public Map<String, Integer> getBankPartitionMapping() {
        return bankPartitionMapping;
    }
}
