package com.banking.kafka.partitioner;

import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BankingHierarchicalPartitioner
 */
class BankingHierarchicalPartitionerTest {

    private BankingHierarchicalPartitioner partitioner;
    private Cluster cluster;
    private static final String TEST_TOPIC = "test-topic";

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        partitioner = new BankingHierarchicalPartitioner();

        // Create a cluster with 3 partitions for testing
        Node node = new Node(0, "localhost", 9092);
        List<PartitionInfo> partitions = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            partitions.add(new PartitionInfo(TEST_TOPIC, i, node, new Node[]{node}, new Node[]{node}));
        }
        cluster = new Cluster("test-cluster", List.of(node), partitions,
                Collections.emptySet(), Collections.emptySet());
    }

    @AfterEach
    void tearDown() {
        if (partitioner != null) {
            partitioner.close();
        }
    }

    @Test
    void testPartitionWithInstitutionId() {
        Map<String, Object> config = new HashMap<>();
        partitioner.configure(config);

        Headers headers = new RecordHeaders();
        headers.add("X-Institution-Id", "BNK001".getBytes(StandardCharsets.UTF_8));

        int partition = partitioner.partition(TEST_TOPIC, null, null, "test-value",
                "test-value".getBytes(), cluster, headers);

        assertTrue(partition >= 0 && partition < 3, "Partition should be between 0 and 2");
    }

    @Test
    void testConsistentPartitioning() {
        Map<String, Object> config = new HashMap<>();
        partitioner.configure(config);

        String institutionId = "BNK001";

        int partition1 = partitionWithInstitutionId(institutionId);
        int partition2 = partitionWithInstitutionId(institutionId);
        int partition3 = partitionWithInstitutionId(institutionId);

        assertEquals(partition1, partition2, "Same institution should route to same partition");
        assertEquals(partition2, partition3, "Same institution should route to same partition");
    }

    @Test
    void testDifferentInstitutionsMayRouteToDifferentPartitions() {
        Map<String, Object> config = new HashMap<>();
        partitioner.configure(config);

        int partition1 = partitionWithInstitutionId("BNK001");
        int partition2 = partitionWithInstitutionId("BNK002");
        int partition3 = partitionWithInstitutionId("BNK003");

        assertTrue(partition1 >= 0 && partition1 < 3);
        assertTrue(partition2 >= 0 && partition2 < 3);
        assertTrue(partition3 >= 0 && partition3 < 3);
    }

    @Test
    void testMissingInstitutionIdUsesDefaultPartition() {
        Map<String, Object> config = new HashMap<>();
        partitioner.configure(config);

        Headers headers = new RecordHeaders();

        int partition = partitioner.partition(TEST_TOPIC, null, null, "test-value",
                "test-value".getBytes(), cluster, headers);

        assertEquals(0, partition, "Missing institution ID should use default partition");
    }

    @Test
    void testCustomDefaultPartition() {
        Map<String, Object> config = new HashMap<>();
        config.put("default.partition", 2);
        partitioner.configure(config);

        Headers headers = new RecordHeaders();

        int partition = partitioner.partition(TEST_TOPIC, null, null, "test-value",
                "test-value".getBytes(), cluster, headers);

        assertEquals(2, partition, "Should use custom default partition");
    }

    @Test
    void testCustomInstitutionIdHeader() {
        Map<String, Object> config = new HashMap<>();
        config.put("institution.id.header", "X-Bank-Id");
        partitioner.configure(config);

        Headers headers = new RecordHeaders();
        headers.add("X-Bank-Id", "BNK001".getBytes(StandardCharsets.UTF_8));

        int partition = partitioner.partition(TEST_TOPIC, null, null, "test-value",
                "test-value".getBytes(), cluster, headers);

        assertTrue(partition >= 0 && partition < 3);
        assertEquals("X-Bank-Id", partitioner.getInstitutionIdHeader());
    }

    @Test
    void testNullHeaders() {
        Map<String, Object> config = new HashMap<>();
        partitioner.configure(config);

        int partition = partitioner.partition(TEST_TOPIC, null, null, "test-value",
                "test-value".getBytes(), cluster, null);

        assertEquals(0, partition, "Null headers should use default partition");
    }

    @Test
    void testEmptyInstitutionId() {
        Map<String, Object> config = new HashMap<>();
        partitioner.configure(config);

        Headers headers = new RecordHeaders();
        headers.add("X-Institution-Id", "".getBytes(StandardCharsets.UTF_8));

        int partition = partitioner.partition(TEST_TOPIC, null, null, "test-value",
                "test-value".getBytes(), cluster, headers);

        assertEquals(0, partition, "Empty institution ID should use default partition");
    }

    @Test
    void testPartitionWithoutHeadersApi() {
        Map<String, Object> config = new HashMap<>();
        partitioner.configure(config);

        int partition = partitioner.partition(TEST_TOPIC, null, null, "test-value",
                "test-value".getBytes(), cluster);

        assertEquals(0, partition, "Old API should use default partition");
    }

    @Test
    void testDefaultPartitionBoundedByAvailablePartitions() {
        Map<String, Object> config = new HashMap<>();
        config.put("default.partition", 10);
        partitioner.configure(config);

        Headers headers = new RecordHeaders();

        int partition = partitioner.partition(TEST_TOPIC, null, null, "test-value",
                "test-value".getBytes(), cluster, headers);

        assertEquals(2, partition, "Default partition should be bounded by available partitions");
    }

    // --- Deterministic mapping tests ---

    @Test
    void testDeterministicMappingFromFile() throws IOException {
        Path mappingFile = tempDir.resolve("bank-mapping.csv");
        Files.writeString(mappingFile, "# Bank partition mapping\nBNK001,0\nBNK002,1\nBNK003,2\n");

        Map<String, Object> config = new HashMap<>();
        config.put("bank.partition.mapping.file", mappingFile.toString());
        partitioner.configure(config);

        assertEquals(0, partitionWithInstitutionId("BNK001"));
        assertEquals(1, partitionWithInstitutionId("BNK002"));
        assertEquals(2, partitionWithInstitutionId("BNK003"));
    }

    @Test
    void testDeterministicMappingOverridesHash() throws IOException {
        Path mappingFile = tempDir.resolve("bank-mapping.csv");
        Files.writeString(mappingFile, "BNK001,1\n");

        Map<String, Object> config = new HashMap<>();
        config.put("bank.partition.mapping.file", mappingFile.toString());
        partitioner.configure(config);

        // BNK001 is explicitly mapped to partition 1
        assertEquals(1, partitionWithInstitutionId("BNK001"));

        // BNK999 is not in the mapping, falls back to Murmur2
        int fallbackPartition = partitionWithInstitutionId("BNK999");
        assertTrue(fallbackPartition >= 0 && fallbackPartition < 3);
    }

    @Test
    void testDeterministicMappingModuloPartitions() throws IOException {
        // Mapping assigns partition 15 but cluster has only 3 partitions
        Path mappingFile = tempDir.resolve("bank-mapping.csv");
        Files.writeString(mappingFile, "BNK001,15\n");

        Map<String, Object> config = new HashMap<>();
        config.put("bank.partition.mapping.file", mappingFile.toString());
        partitioner.configure(config);

        // 15 % 3 = 0
        assertEquals(0, partitionWithInstitutionId("BNK001"));
    }

    @Test
    void testMurmur2FallbackIsConsistent() {
        Map<String, Object> config = new HashMap<>();
        partitioner.configure(config);

        int p1 = partitionWithInstitutionId("BNK_UNMAPPED_001");
        int p2 = partitionWithInstitutionId("BNK_UNMAPPED_001");
        assertEquals(p1, p2, "Murmur2 should produce consistent partitions");
    }

    @Test
    void testMurmur2DistributionWith20Partitions() {
        // Create a cluster with 20 partitions
        Node node = new Node(0, "localhost", 9092);
        List<PartitionInfo> partitions = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            partitions.add(new PartitionInfo(TEST_TOPIC, i, node, new Node[]{node}, new Node[]{node}));
        }
        Cluster cluster20 = new Cluster("test-cluster-20", List.of(node), partitions,
                Collections.emptySet(), Collections.emptySet());

        Map<String, Object> config = new HashMap<>();
        partitioner.configure(config);

        // Generate 200 bank codes and check distribution
        Map<Integer, Integer> distribution = new HashMap<>();
        for (int i = 1; i <= 200; i++) {
            String bankCode = String.format("BNK%03d", i);
            Headers headers = new RecordHeaders();
            headers.add("X-Institution-Id", bankCode.getBytes(StandardCharsets.UTF_8));
            int p = partitioner.partition(TEST_TOPIC, null, null, "v", "v".getBytes(), cluster20, headers);
            distribution.merge(p, 1, Integer::sum);
        }

        // With 200 banks and 20 partitions, average is 10 per partition.
        // Murmur2 should give reasonable distribution — no partition should have more than 25.
        int max = distribution.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        assertTrue(max <= 25, "Murmur2 distribution should not create extreme hotspots, max=" + max);

        // All partitions should receive at least some banks
        assertTrue(distribution.size() >= 15,
                "At least 15 of 20 partitions should be used with 200 banks, got " + distribution.size());
    }

    @Test
    void testLoadMappingFileWithComments() throws IOException {
        Path mappingFile = tempDir.resolve("bank-mapping.csv");
        Files.writeString(mappingFile,
                "# This is a comment\n" +
                "\n" +
                "BNK001,0\n" +
                "# Another comment\n" +
                "BNK002,5\n");

        Map<String, Integer> mapping = BankingHierarchicalPartitioner.loadMappingFile(mappingFile.toString());
        assertEquals(2, mapping.size());
        assertEquals(0, mapping.get("BNK001"));
        assertEquals(5, mapping.get("BNK002"));
    }

    @Test
    void testLoadMappingFileNonExistent() {
        Map<String, Integer> mapping = BankingHierarchicalPartitioner.loadMappingFile("/nonexistent/file.csv");
        assertTrue(mapping.isEmpty());
    }

    @Test
    void testNoMappingFileUsesEmptyMap() {
        Map<String, Object> config = new HashMap<>();
        partitioner.configure(config);

        assertTrue(partitioner.getBankPartitionMapping().isEmpty());
    }

    // Helper
    private int partitionWithInstitutionId(String institutionId) {
        Headers headers = new RecordHeaders();
        headers.add("X-Institution-Id", institutionId.getBytes(StandardCharsets.UTF_8));
        return partitioner.partition(TEST_TOPIC, null, null, "test-value",
                "test-value".getBytes(), cluster, headers);
    }
}
