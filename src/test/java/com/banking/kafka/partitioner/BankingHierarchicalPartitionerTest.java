package com.banking.kafka.partitioner;

import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BankingHierarchicalPartitioner
 */
class BankingHierarchicalPartitionerTest {

    private BankingHierarchicalPartitioner partitioner;
    private Cluster cluster;
    private static final String TEST_TOPIC = "test-topic";

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
                java.util.Collections.emptySet(), java.util.Collections.emptySet());
    }

    @AfterEach
    void tearDown() {
        if (partitioner != null) {
            partitioner.close();
        }
    }

    @Test
    void testPartitionWithInstitutionId() {
        // Configure partitioner
        Map<String, Object> config = new HashMap<>();
        partitioner.configure(config);

        // Create headers with institution ID
        Headers headers = new RecordHeaders();
        headers.add("X-Institution-Id", "BNK001".getBytes(StandardCharsets.UTF_8));

        // Partition message
        int partition = partitioner.partition(TEST_TOPIC, null, null, "test-value",
                "test-value".getBytes(), cluster, headers);

        // Verify partition is valid
        assertTrue(partition >= 0 && partition < 3, "Partition should be between 0 and 2");
    }

    @Test
    void testConsistentPartitioning() {
        // Configure partitioner
        Map<String, Object> config = new HashMap<>();
        partitioner.configure(config);

        String institutionId = "BNK001";

        // Partition same institution ID multiple times
        int partition1 = partitionWithInstitutionId(institutionId);
        int partition2 = partitionWithInstitutionId(institutionId);
        int partition3 = partitionWithInstitutionId(institutionId);

        // Verify all messages go to same partition
        assertEquals(partition1, partition2, "Same institution should route to same partition");
        assertEquals(partition2, partition3, "Same institution should route to same partition");
    }

    @Test
    void testDifferentInstitutionsMayRouteToDifferentPartitions() {
        // Configure partitioner
        Map<String, Object> config = new HashMap<>();
        partitioner.configure(config);

        // Test multiple different institutions
        // We can't guarantee they'll be on different partitions with only 3 partitions,
        // but we can verify they get valid partition assignments
        int partition1 = partitionWithInstitutionId("BNK001");
        int partition2 = partitionWithInstitutionId("BNK002");
        int partition3 = partitionWithInstitutionId("BNK003");

        // All should be valid partitions
        assertTrue(partition1 >= 0 && partition1 < 3);
        assertTrue(partition2 >= 0 && partition2 < 3);
        assertTrue(partition3 >= 0 && partition3 < 3);
    }

    @Test
    void testMissingInstitutionIdUsesDefaultPartition() {
        // Configure partitioner
        Map<String, Object> config = new HashMap<>();
        partitioner.configure(config);

        // Create headers without institution ID
        Headers headers = new RecordHeaders();

        // Partition message
        int partition = partitioner.partition(TEST_TOPIC, null, null, "test-value",
                "test-value".getBytes(), cluster, headers);

        // Should use default partition (0)
        assertEquals(0, partition, "Missing institution ID should use default partition");
    }

    @Test
    void testCustomDefaultPartition() {
        // Configure partitioner with custom default partition
        Map<String, Object> config = new HashMap<>();
        config.put("default.partition", 2);
        partitioner.configure(config);

        // Create headers without institution ID
        Headers headers = new RecordHeaders();

        // Partition message
        int partition = partitioner.partition(TEST_TOPIC, null, null, "test-value",
                "test-value".getBytes(), cluster, headers);

        // Should use custom default partition (2)
        assertEquals(2, partition, "Should use custom default partition");
    }

    @Test
    void testCustomInstitutionIdHeader() {
        // Configure partitioner with custom header name
        Map<String, Object> config = new HashMap<>();
        config.put("institution.id.header", "X-Bank-Id");
        partitioner.configure(config);

        // Create headers with custom header name
        Headers headers = new RecordHeaders();
        headers.add("X-Bank-Id", "BNK001".getBytes(StandardCharsets.UTF_8));

        // Partition message
        int partition = partitioner.partition(TEST_TOPIC, null, null, "test-value",
                "test-value".getBytes(), cluster, headers);

        // Verify partition is valid
        assertTrue(partition >= 0 && partition < 3);

        // Verify the partitioner is using the custom header
        assertEquals("X-Bank-Id", partitioner.getInstitutionIdHeader());
    }

    @Test
    void testNullHeaders() {
        // Configure partitioner
        Map<String, Object> config = new HashMap<>();
        partitioner.configure(config);

        // Partition message with null headers
        int partition = partitioner.partition(TEST_TOPIC, null, null, "test-value",
                "test-value".getBytes(), cluster, null);

        // Should use default partition (0)
        assertEquals(0, partition, "Null headers should use default partition");
    }

    @Test
    void testEmptyInstitutionId() {
        // Configure partitioner
        Map<String, Object> config = new HashMap<>();
        partitioner.configure(config);

        // Create headers with empty institution ID
        Headers headers = new RecordHeaders();
        headers.add("X-Institution-Id", "".getBytes(StandardCharsets.UTF_8));

        // Partition message
        int partition = partitioner.partition(TEST_TOPIC, null, null, "test-value",
                "test-value".getBytes(), cluster, headers);

        // Should use default partition
        assertEquals(0, partition, "Empty institution ID should use default partition");
    }

    @Test
    void testPartitionWithoutHeadersApi() {
        // Configure partitioner
        Map<String, Object> config = new HashMap<>();
        partitioner.configure(config);

        // Call old API without headers
        int partition = partitioner.partition(TEST_TOPIC, null, null, "test-value",
                "test-value".getBytes(), cluster);

        // Should use default partition
        assertEquals(0, partition, "Old API should use default partition");
    }

    @Test
    void testDefaultPartitionBoundedByAvailablePartitions() {
        // Configure partitioner with default partition > available partitions
        Map<String, Object> config = new HashMap<>();
        config.put("default.partition", 10); // Higher than available partitions (3)
        partitioner.configure(config);

        // Create headers without institution ID
        Headers headers = new RecordHeaders();

        // Partition message
        int partition = partitioner.partition(TEST_TOPIC, null, null, "test-value",
                "test-value".getBytes(), cluster, headers);

        // Should be bounded to last available partition
        assertEquals(2, partition, "Default partition should be bounded by available partitions");
    }

    // Helper method to partition with a specific institution ID
    private int partitionWithInstitutionId(String institutionId) {
        Headers headers = new RecordHeaders();
        headers.add("X-Institution-Id", institutionId.getBytes(StandardCharsets.UTF_8));
        return partitioner.partition(TEST_TOPIC, null, null, "test-value",
                "test-value".getBytes(), cluster, headers);
    }
}
