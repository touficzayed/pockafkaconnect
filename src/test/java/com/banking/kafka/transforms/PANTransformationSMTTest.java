package com.banking.kafka.transforms;

import org.apache.kafka.connect.sink.SinkRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PANTransformationSMT
 */
class PANTransformationSMTTest {

    private PANTransformationSMT<SinkRecord> transform;

    @BeforeEach
    void setUp() {
        transform = new PANTransformationSMT<>();
    }

    @AfterEach
    void tearDown() {
        if (transform != null) {
            transform.close();
        }
    }

    @Test
    void testRemoveStrategy_Schemaless() {
        // Configure transform with REMOVE strategy
        Map<String, Object> config = new HashMap<>();
        config.put("strategy", "REMOVE");
        config.put("source.field", "encryptedPrimaryAccountNumber");
        transform.configure(config);

        // Create record with encrypted PAN
        Map<String, Object> value = new HashMap<>();
        value.put("transactionId", "txn-123");
        value.put("amount", 100.0);
        value.put("encryptedPrimaryAccountNumber", "eyJhbGciOiJSU0EtT0FFUC0yNTYi...");

        SinkRecord record = new SinkRecord(
                "test-topic", 0, null, null,
                null, value, 0
        );

        // Apply transformation
        SinkRecord transformedRecord = transform.apply(record);

        // Verify
        assertNotNull(transformedRecord);
        Map<String, Object> transformedValue = (Map<String, Object>) transformedRecord.value();
        assertFalse(transformedValue.containsKey("encryptedPrimaryAccountNumber"),
                "Encrypted PAN should be removed");
        assertEquals("txn-123", transformedValue.get("transactionId"));
        assertEquals(100.0, transformedValue.get("amount"));
    }

    @Test
    void testRemoveStrategy_FieldNotPresent() {
        // Configure transform
        Map<String, Object> config = new HashMap<>();
        config.put("strategy", "REMOVE");
        transform.configure(config);

        // Create record WITHOUT encrypted PAN
        Map<String, Object> value = new HashMap<>();
        value.put("transactionId", "txn-123");

        SinkRecord record = new SinkRecord(
                "test-topic", 0, null, null,
                null, value, 0
        );

        // Apply transformation - should return record unchanged
        SinkRecord transformedRecord = transform.apply(record);

        assertNotNull(transformedRecord);
        assertEquals(record.value(), transformedRecord.value());
    }

    @Test
    void testNullValue() {
        // Configure transform
        Map<String, Object> config = new HashMap<>();
        config.put("strategy", "REMOVE");
        transform.configure(config);

        // Create record with null value
        SinkRecord record = new SinkRecord(
                "test-topic", 0, null, null,
                null, null, 0
        );

        // Apply transformation - should return record unchanged
        SinkRecord transformedRecord = transform.apply(record);

        assertNotNull(transformedRecord);
        assertNull(transformedRecord.value());
    }
}
