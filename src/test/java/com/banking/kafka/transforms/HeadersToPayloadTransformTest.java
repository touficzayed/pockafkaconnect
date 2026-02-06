package com.banking.kafka.transforms;

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.header.ConnectHeaders;
import org.apache.kafka.connect.sink.SinkRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for HeadersToPayloadTransform
 */
class HeadersToPayloadTransformTest {

    private HeadersToPayloadTransform<SinkRecord> transform;

    @BeforeEach
    void setUp() {
        transform = new HeadersToPayloadTransform<>();
    }

    @AfterEach
    void tearDown() {
        if (transform != null) {
            transform.close();
        }
    }

    @Test
    void testSchemalessWithAllHeadersPresent() {
        // Configure transform
        Map<String, Object> config = new HashMap<>();
        config.put("mandatory.headers", "X-Institution-Id,X-Event-Type");
        config.put("optional.headers", "X-User-Id");
        config.put("target.field", "headers");
        transform.configure(config);

        // Create record with headers
        Map<String, Object> value = new HashMap<>();
        value.put("transactionId", "txn-123");
        value.put("amount", 100.0);

        ConnectHeaders headers = new ConnectHeaders();
        headers.addString("X-Institution-Id", "BNK001");
        headers.addString("X-Event-Type", "PAYMENT");
        headers.addString("X-User-Id", "user-456");

        SinkRecord record = new SinkRecord(
                "test-topic", 0, null, null,
                null, value, 0, null, null, headers
        );

        // Apply transformation
        SinkRecord transformedRecord = transform.apply(record);

        // Verify
        assertNotNull(transformedRecord);
        Map<String, Object> transformedValue = (Map<String, Object>) transformedRecord.value();
        assertTrue(transformedValue.containsKey("headers"));

        Map<String, String> extractedHeaders = (Map<String, String>) transformedValue.get("headers");
        assertEquals("BNK001", extractedHeaders.get("X-Institution-Id"));
        assertEquals("PAYMENT", extractedHeaders.get("X-Event-Type"));
        assertEquals("user-456", extractedHeaders.get("X-User-Id"));
        assertEquals("txn-123", transformedValue.get("transactionId"));
        assertEquals(100.0, transformedValue.get("amount"));
    }

    @Test
    void testSchemalessWithMissingOptionalHeader() {
        // Configure transform
        Map<String, Object> config = new HashMap<>();
        config.put("mandatory.headers", "X-Institution-Id");
        config.put("optional.headers", "X-User-Id");
        config.put("target.field", "headers");
        transform.configure(config);

        // Create record without optional header
        Map<String, Object> value = new HashMap<>();
        value.put("transactionId", "txn-123");

        ConnectHeaders headers = new ConnectHeaders();
        headers.addString("X-Institution-Id", "BNK001");

        SinkRecord record = new SinkRecord(
                "test-topic", 0, null, null,
                null, value, 0, null, null, headers
        );

        // Apply transformation - should succeed
        SinkRecord transformedRecord = transform.apply(record);

        // Verify
        assertNotNull(transformedRecord);
        Map<String, Object> transformedValue = (Map<String, Object>) transformedRecord.value();
        Map<String, String> extractedHeaders = (Map<String, String>) transformedValue.get("headers");
        assertEquals("BNK001", extractedHeaders.get("X-Institution-Id"));
        assertFalse(extractedHeaders.containsKey("X-User-Id"));
    }

    @Test
    void testSchemalessWithMissingMandatoryHeaderShouldFail() {
        // Configure transform to fail on missing mandatory headers
        Map<String, Object> config = new HashMap<>();
        config.put("mandatory.headers", "X-Institution-Id,X-Event-Type");
        config.put("optional.headers", "");
        config.put("fail.on.missing.mandatory", true);
        transform.configure(config);

        // Create record missing mandatory header
        Map<String, Object> value = new HashMap<>();
        value.put("transactionId", "txn-123");

        ConnectHeaders headers = new ConnectHeaders();
        headers.addString("X-Institution-Id", "BNK001");
        // Missing X-Event-Type

        SinkRecord record = new SinkRecord(
                "test-topic", 0, null, null,
                null, value, 0, null, null, headers
        );

        // Apply transformation - should throw exception
        assertThrows(DataException.class, () -> transform.apply(record));
    }

    @Test
    void testSchemalessWithMissingMandatoryHeaderShouldNotFailIfConfigured() {
        // Configure transform to NOT fail on missing mandatory headers
        Map<String, Object> config = new HashMap<>();
        config.put("mandatory.headers", "X-Institution-Id,X-Event-Type");
        config.put("optional.headers", "");
        config.put("fail.on.missing.mandatory", false);
        transform.configure(config);

        // Create record missing mandatory header
        Map<String, Object> value = new HashMap<>();
        value.put("transactionId", "txn-123");

        ConnectHeaders headers = new ConnectHeaders();
        headers.addString("X-Institution-Id", "BNK001");
        // Missing X-Event-Type

        SinkRecord record = new SinkRecord(
                "test-topic", 0, null, null,
                null, value, 0, null, null, headers
        );

        // Apply transformation - should succeed
        SinkRecord transformedRecord = transform.apply(record);

        // Verify
        assertNotNull(transformedRecord);
        Map<String, Object> transformedValue = (Map<String, Object>) transformedRecord.value();
        Map<String, String> extractedHeaders = (Map<String, String>) transformedValue.get("headers");
        assertEquals("BNK001", extractedHeaders.get("X-Institution-Id"));
        assertFalse(extractedHeaders.containsKey("X-Event-Type"));
    }

    @Test
    void testWithSchema() {
        // Configure transform
        Map<String, Object> config = new HashMap<>();
        config.put("mandatory.headers", "X-Institution-Id");
        config.put("optional.headers", "");
        transform.configure(config);

        // Create schema
        Schema valueSchema = SchemaBuilder.struct()
                .field("transactionId", Schema.STRING_SCHEMA)
                .field("amount", Schema.FLOAT64_SCHEMA)
                .build();

        // Create value
        Struct value = new Struct(valueSchema);
        value.put("transactionId", "txn-123");
        value.put("amount", 100.0);

        // Create headers
        ConnectHeaders headers = new ConnectHeaders();
        headers.addString("X-Institution-Id", "BNK001");

        SinkRecord record = new SinkRecord(
                "test-topic", 0, null, null,
                valueSchema, value, 0, null, null, headers
        );

        // Apply transformation
        SinkRecord transformedRecord = transform.apply(record);

        // Verify
        assertNotNull(transformedRecord);
        assertNotNull(transformedRecord.valueSchema());

        Struct transformedValue = (Struct) transformedRecord.value();
        assertEquals("txn-123", transformedValue.get("transactionId"));
        assertEquals(100.0, transformedValue.get("amount"));

        Map<String, String> extractedHeaders = (Map<String, String>) transformedValue.get("headers");
        assertEquals("BNK001", extractedHeaders.get("X-Institution-Id"));
    }

    @Test
    void testNullValue() {
        // Configure transform
        Map<String, Object> config = new HashMap<>();
        config.put("mandatory.headers", "X-Institution-Id");
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

    @Test
    void testCustomTargetField() {
        // Configure transform with custom target field
        Map<String, Object> config = new HashMap<>();
        config.put("mandatory.headers", "X-Institution-Id");
        config.put("target.field", "metadata");
        transform.configure(config);

        // Create record
        Map<String, Object> value = new HashMap<>();
        value.put("transactionId", "txn-123");

        ConnectHeaders headers = new ConnectHeaders();
        headers.addString("X-Institution-Id", "BNK001");

        SinkRecord record = new SinkRecord(
                "test-topic", 0, null, null,
                null, value, 0, null, null, headers
        );

        // Apply transformation
        SinkRecord transformedRecord = transform.apply(record);

        // Verify custom field name
        Map<String, Object> transformedValue = (Map<String, Object>) transformedRecord.value();
        assertTrue(transformedValue.containsKey("metadata"));
        assertFalse(transformedValue.containsKey("headers"));
    }

    @Test
    void testSchemalessWithWrapPayload() {
        // Configure transform with wrap.payload=true
        Map<String, Object> config = new HashMap<>();
        config.put("mandatory.headers", "X-Institution-Id,X-Event-Type");
        config.put("optional.headers", "X-Version");
        config.put("target.field", "headers");
        config.put("wrap.payload", true);
        config.put("payload.field", "payload");
        transform.configure(config);

        // Create record with headers
        Map<String, Object> value = new HashMap<>();
        value.put("transactionId", "txn-123");
        value.put("amount", 100.0);

        ConnectHeaders headers = new ConnectHeaders();
        headers.addString("X-Institution-Id", "BNK001");
        headers.addString("X-Event-Type", "PAYMENT");
        headers.addString("X-Version", "1.0");

        SinkRecord record = new SinkRecord(
                "test-topic", 0, null, null,
                null, value, 0, null, null, headers
        );

        // Apply transformation
        SinkRecord transformedRecord = transform.apply(record);

        // Verify wrapped format: {"headers": {...}, "payload": {...}}
        assertNotNull(transformedRecord);
        Map<String, Object> transformedValue = (Map<String, Object>) transformedRecord.value();

        // Should have only 2 fields: headers and payload
        assertEquals(2, transformedValue.size());
        assertTrue(transformedValue.containsKey("headers"));
        assertTrue(transformedValue.containsKey("payload"));

        // Verify headers
        Map<String, String> extractedHeaders = (Map<String, String>) transformedValue.get("headers");
        assertEquals("BNK001", extractedHeaders.get("X-Institution-Id"));
        assertEquals("PAYMENT", extractedHeaders.get("X-Event-Type"));
        assertEquals("1.0", extractedHeaders.get("X-Version"));

        // Verify payload contains original fields
        Map<String, Object> payload = (Map<String, Object>) transformedValue.get("payload");
        assertEquals("txn-123", payload.get("transactionId"));
        assertEquals(100.0, payload.get("amount"));
    }

    @Test
    void testSchemalessWithWrapPayloadCustomFieldNames() {
        // Configure transform with custom field names
        Map<String, Object> config = new HashMap<>();
        config.put("mandatory.headers", "X-Institution-Id");
        config.put("target.field", "metadata");
        config.put("wrap.payload", true);
        config.put("payload.field", "data");
        transform.configure(config);

        // Create record
        Map<String, Object> value = new HashMap<>();
        value.put("transactionId", "txn-456");

        ConnectHeaders headers = new ConnectHeaders();
        headers.addString("X-Institution-Id", "BNK002");

        SinkRecord record = new SinkRecord(
                "test-topic", 0, null, null,
                null, value, 0, null, null, headers
        );

        // Apply transformation
        SinkRecord transformedRecord = transform.apply(record);

        // Verify custom field names: {"metadata": {...}, "data": {...}}
        Map<String, Object> transformedValue = (Map<String, Object>) transformedRecord.value();
        assertTrue(transformedValue.containsKey("metadata"));
        assertTrue(transformedValue.containsKey("data"));
        assertFalse(transformedValue.containsKey("headers"));
        assertFalse(transformedValue.containsKey("payload"));

        Map<String, String> extractedHeaders = (Map<String, String>) transformedValue.get("metadata");
        assertEquals("BNK002", extractedHeaders.get("X-Institution-Id"));

        Map<String, Object> payload = (Map<String, Object>) transformedValue.get("data");
        assertEquals("txn-456", payload.get("transactionId"));
    }

    @Test
    void testWithSchemaAndWrapPayload() {
        // Configure transform with wrap.payload=true
        Map<String, Object> config = new HashMap<>();
        config.put("mandatory.headers", "X-Institution-Id");
        config.put("wrap.payload", true);
        transform.configure(config);

        // Create schema
        Schema valueSchema = SchemaBuilder.struct()
                .field("transactionId", Schema.STRING_SCHEMA)
                .field("amount", Schema.FLOAT64_SCHEMA)
                .build();

        // Create value
        Struct value = new Struct(valueSchema);
        value.put("transactionId", "txn-789");
        value.put("amount", 250.0);

        // Create headers
        ConnectHeaders headers = new ConnectHeaders();
        headers.addString("X-Institution-Id", "BNK003");

        SinkRecord record = new SinkRecord(
                "test-topic", 0, null, null,
                valueSchema, value, 0, null, null, headers
        );

        // Apply transformation
        SinkRecord transformedRecord = transform.apply(record);

        // Verify
        assertNotNull(transformedRecord);
        assertNotNull(transformedRecord.valueSchema());

        Struct transformedValue = (Struct) transformedRecord.value();

        // Verify headers field
        Map<String, String> extractedHeaders = (Map<String, String>) transformedValue.get("headers");
        assertEquals("BNK003", extractedHeaders.get("X-Institution-Id"));

        // Verify payload field contains original struct
        Struct payload = (Struct) transformedValue.get("payload");
        assertEquals("txn-789", payload.get("transactionId"));
        assertEquals(250.0, payload.get("amount"));
    }
}
