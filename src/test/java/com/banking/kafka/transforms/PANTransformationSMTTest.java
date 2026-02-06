package com.banking.kafka.transforms;

import com.banking.kafka.crypto.JWEHandler;
import org.apache.kafka.connect.header.ConnectHeaders;
import org.apache.kafka.connect.header.Headers;
import org.apache.kafka.connect.sink.SinkRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
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

    @Test
    void testMaskStrategy_Schemaless() {
        // Configure transform with MASK strategy
        Map<String, Object> config = new HashMap<>();
        config.put("strategy", "MASK");
        config.put("source.field", "pan");
        config.put("target.field", "maskedPan");
        transform.configure(config);

        // Create record with plaintext PAN
        Map<String, Object> value = new HashMap<>();
        value.put("transactionId", "txn-123");
        value.put("pan", "4532015112830366");

        SinkRecord record = new SinkRecord(
                "test-topic", 0, null, null,
                null, value, 0
        );

        // Apply transformation
        SinkRecord transformedRecord = transform.apply(record);

        // Verify
        assertNotNull(transformedRecord);
        Map<String, Object> transformedValue = (Map<String, Object>) transformedRecord.value();
        assertFalse(transformedValue.containsKey("pan"), "Original PAN should be removed");
        assertTrue(transformedValue.containsKey("maskedPan"), "Masked PAN should be present");

        String maskedPan = (String) transformedValue.get("maskedPan");
        // Format: first 6 + masked middle + last 4 = 453201******0366
        assertEquals("453201******0366", maskedPan);
    }

    @Test
    void testMaskStrategy_ShortPAN() {
        Map<String, Object> config = new HashMap<>();
        config.put("strategy", "MASK");
        config.put("source.field", "pan");
        config.put("target.field", "maskedPan");
        transform.configure(config);

        // PAN shorter than prefix + suffix (10 chars)
        Map<String, Object> value = new HashMap<>();
        value.put("pan", "12345678");

        SinkRecord record = new SinkRecord(
                "test-topic", 0, null, null,
                null, value, 0
        );

        SinkRecord transformedRecord = transform.apply(record);
        Map<String, Object> transformedValue = (Map<String, Object>) transformedRecord.value();

        // Too short to mask, should return as-is
        assertEquals("12345678", transformedValue.get("maskedPan"));
    }

    @Test
    void testRuleBasedStrategy() {
        // Configure with rules
        Map<String, Object> config = new HashMap<>();
        config.put("default.strategy", "REMOVE");
        config.put("rules", "BNK001:PAYMENT:*:MASK,BNK002:*:*:REMOVE");
        config.put("source.field", "pan");
        config.put("target.field", "maskedPan");
        transform.configure(config);

        // Test BNK001 + PAYMENT -> should MASK
        Headers headers1 = new ConnectHeaders();
        headers1.addString("X-Institution-Id", "BNK001");
        headers1.addString("X-Event-Type", "PAYMENT");
        headers1.addString("X-Version", "1.0");

        Map<String, Object> value1 = new HashMap<>();
        value1.put("pan", "4532015112830366");

        SinkRecord record1 = new SinkRecord(
                "test-topic", 0, null, null,
                null, value1, 0, null, null, headers1
        );

        SinkRecord result1 = transform.apply(record1);
        Map<String, Object> resultValue1 = (Map<String, Object>) result1.value();
        assertTrue(resultValue1.containsKey("maskedPan"), "Should have masked PAN for BNK001:PAYMENT");
        assertEquals("453201******0366", resultValue1.get("maskedPan"));
    }

    @Test
    void testRuleBasedStrategy_DefaultFallback() {
        Map<String, Object> config = new HashMap<>();
        config.put("default.strategy", "REMOVE");
        config.put("rules", "BNK001:PAYMENT:*:MASK");
        config.put("source.field", "pan");
        transform.configure(config);

        // Unknown institution -> should use default REMOVE
        Headers headers = new ConnectHeaders();
        headers.addString("X-Institution-Id", "UNKNOWN");
        headers.addString("X-Event-Type", "OTHER");

        Map<String, Object> value = new HashMap<>();
        value.put("pan", "4532015112830366");

        SinkRecord record = new SinkRecord(
                "test-topic", 0, null, null,
                null, value, 0, null, null, headers
        );

        SinkRecord result = transform.apply(record);
        Map<String, Object> resultValue = (Map<String, Object>) result.value();
        assertFalse(resultValue.containsKey("pan"), "PAN should be removed for unknown institution");
    }

    @Test
    void testDecryptStrategy(@TempDir Path tempDir) throws Exception {
        // Generate RSA key pair
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        KeyPair keyPair = keyGen.generateKeyPair();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();

        // Write private key to temp file
        File privateKeyFile = tempDir.resolve("private.pem").toFile();
        String pemContent = "-----BEGIN PRIVATE KEY-----\n" +
                Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(privateKey.getEncoded()) +
                "\n-----END PRIVATE KEY-----\n";
        Files.write(privateKeyFile.toPath(), pemContent.getBytes());

        // Encrypt a PAN
        JWEHandler jweHandler = new JWEHandler();
        String pan = "4532015112830366";
        String encryptedPan = jweHandler.encrypt(pan, publicKey);

        // Configure transform with DECRYPT strategy
        Map<String, Object> config = new HashMap<>();
        config.put("strategy", "DECRYPT");
        config.put("source.field", "encryptedPan");
        config.put("target.field", "decryptedPan");
        config.put("private.key.path", privateKeyFile.getAbsolutePath());
        transform.configure(config);

        // Create record with encrypted PAN
        Map<String, Object> value = new HashMap<>();
        value.put("transactionId", "txn-123");
        value.put("encryptedPan", encryptedPan);

        SinkRecord record = new SinkRecord(
                "test-topic", 0, null, null,
                null, value, 0
        );

        // Apply transformation
        SinkRecord transformedRecord = transform.apply(record);

        // Verify
        assertNotNull(transformedRecord);
        Map<String, Object> transformedValue = (Map<String, Object>) transformedRecord.value();
        assertFalse(transformedValue.containsKey("encryptedPan"), "Encrypted PAN should be removed");
        assertTrue(transformedValue.containsKey("decryptedPan"), "Decrypted PAN should be present");
        assertEquals(pan, transformedValue.get("decryptedPan"), "Decrypted PAN should match original");
    }
}
