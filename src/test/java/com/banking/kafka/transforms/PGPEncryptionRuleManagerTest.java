package com.banking.kafka.transforms;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PGPEncryptionRuleManager.
 */
class PGPEncryptionRuleManagerTest {

    @Test
    void testRuleWithWildcards() {
        String rules = "PAYMENT:*:ENCRYPT,REFUND:v1:SKIP,*:*:ENCRYPT";
        PGPEncryptionRuleManager manager = new PGPEncryptionRuleManager(
            rules, PGPEncryptionRuleManager.Action.ENCRYPT);

        // PAYMENT with any version should encrypt
        assertTrue(manager.shouldEncrypt("PAYMENT", "v1"));
        assertTrue(manager.shouldEncrypt("PAYMENT", "v2"));
        assertTrue(manager.shouldEncrypt("PAYMENT", "2.0"));

        // REFUND v1 should skip
        assertFalse(manager.shouldEncrypt("REFUND", "v1"));

        // REFUND v2 should encrypt (matches *:*:ENCRYPT)
        assertTrue(manager.shouldEncrypt("REFUND", "v2"));

        // UNKNOWN type should encrypt (matches *:*:ENCRYPT)
        assertTrue(manager.shouldEncrypt("UNKNOWN", "v1"));
    }

    @Test
    void testEmptyRules() {
        PGPEncryptionRuleManager manager = new PGPEncryptionRuleManager(
            "", PGPEncryptionRuleManager.Action.ENCRYPT);

        // With no rules, should use default (ENCRYPT)
        assertTrue(manager.shouldEncrypt("PAYMENT", "v1"));
        assertTrue(manager.shouldEncrypt("REFUND", "v2"));
    }

    @Test
    void testNullRules() {
        PGPEncryptionRuleManager manager = new PGPEncryptionRuleManager(
            null, PGPEncryptionRuleManager.Action.ENCRYPT);

        // With null rules, should use default (ENCRYPT)
        assertTrue(manager.shouldEncrypt("PAYMENT", "v1"));
        assertTrue(manager.shouldEncrypt("REFUND", "v2"));
    }

    @Test
    void testDefaultActionSkip() {
        String rules = "PAYMENT:v1:ENCRYPT";
        PGPEncryptionRuleManager manager = new PGPEncryptionRuleManager(
            rules, PGPEncryptionRuleManager.Action.SKIP);

        // PAYMENT v1 should encrypt (explicit rule)
        assertTrue(manager.shouldEncrypt("PAYMENT", "v1"));

        // PAYMENT v2 should skip (default)
        assertFalse(manager.shouldEncrypt("PAYMENT", "v2"));

        // REFUND should skip (default)
        assertFalse(manager.shouldEncrypt("REFUND", "v1"));
    }

    @Test
    void testCaseInsensitiveMatching() {
        String rules = "payment:v1:SKIP";
        PGPEncryptionRuleManager manager = new PGPEncryptionRuleManager(
            rules, PGPEncryptionRuleManager.Action.ENCRYPT);

        // Case insensitive matching
        assertFalse(manager.shouldEncrypt("PAYMENT", "v1"));
        assertFalse(manager.shouldEncrypt("Payment", "v1"));
        assertFalse(manager.shouldEncrypt("payment", "v1"));

        assertTrue(manager.shouldEncrypt("REFUND", "v1"));
    }

    @Test
    void testMultipleVersions() {
        String rules = "PAYMENT:v1:SKIP,PAYMENT:v2:SKIP,REFUND:*:ENCRYPT";
        PGPEncryptionRuleManager manager = new PGPEncryptionRuleManager(
            rules, PGPEncryptionRuleManager.Action.ENCRYPT);

        // PAYMENT v1 and v2 should skip
        assertFalse(manager.shouldEncrypt("PAYMENT", "v1"));
        assertFalse(manager.shouldEncrypt("PAYMENT", "v2"));

        // PAYMENT v3 should encrypt (default)
        assertTrue(manager.shouldEncrypt("PAYMENT", "v3"));

        // REFUND all versions should encrypt
        assertTrue(manager.shouldEncrypt("REFUND", "v1"));
        assertTrue(manager.shouldEncrypt("REFUND", "v2"));
    }

    @Test
    void testRuleCount() {
        String rules = "PAYMENT:*:ENCRYPT,REFUND:v1:SKIP,*:*:ENCRYPT";
        PGPEncryptionRuleManager manager = new PGPEncryptionRuleManager(
            rules, PGPEncryptionRuleManager.Action.ENCRYPT);

        assertEquals(3, manager.getRuleCount());
    }

    @Test
    void testNullEventTypeAndVersion() {
        String rules = "PAYMENT:*:ENCRYPT";
        PGPEncryptionRuleManager manager = new PGPEncryptionRuleManager(
            rules, PGPEncryptionRuleManager.Action.ENCRYPT);

        // With null values, should return default action
        assertTrue(manager.shouldEncrypt(null, null));
        assertTrue(manager.shouldEncrypt(null, "v1"));
        assertTrue(manager.shouldEncrypt("PAYMENT", null));
    }

    @Test
    void testFirstRuleMatches() {
        // First matching rule should be used
        String rules = "PAYMENT:*:SKIP,PAYMENT:*:ENCRYPT";
        PGPEncryptionRuleManager manager = new PGPEncryptionRuleManager(
            rules, PGPEncryptionRuleManager.Action.ENCRYPT);

        // First rule matches, so should skip
        assertFalse(manager.shouldEncrypt("PAYMENT", "v1"));
    }

    @Test
    void testInvalidRuleFormat() {
        // Invalid format should be caught during initialization
        String rules = "INVALID_RULE";
        PGPEncryptionRuleManager manager = new PGPEncryptionRuleManager(
            rules, PGPEncryptionRuleManager.Action.ENCRYPT);

        // Manager should still work, invalid rule skipped, using default
        assertTrue(manager.shouldEncrypt("PAYMENT", "v1"));
        assertEquals(0, manager.getRuleCount());
    }

    @Test
    void testWhitespaceHandling() {
        String rules = "PAYMENT : v1 : SKIP , REFUND : * : ENCRYPT";
        PGPEncryptionRuleManager manager = new PGPEncryptionRuleManager(
            rules, PGPEncryptionRuleManager.Action.ENCRYPT);

        // Should handle whitespace properly
        // PAYMENT v1 should skip
        assertFalse(manager.shouldEncrypt("PAYMENT", "v1"));
        // REFUND any version should encrypt
        assertTrue(manager.shouldEncrypt("REFUND", "v1"));
        assertTrue(manager.shouldEncrypt("REFUND", "v2"));

        assertEquals(2, manager.getRuleCount());
    }

    @Test
    void testGetAction() {
        String rules = "PAYMENT:v1:SKIP";
        PGPEncryptionRuleManager manager = new PGPEncryptionRuleManager(
            rules, PGPEncryptionRuleManager.Action.ENCRYPT);

        assertEquals(PGPEncryptionRuleManager.Action.SKIP,
                   manager.getAction("PAYMENT", "v1"));
        assertEquals(PGPEncryptionRuleManager.Action.ENCRYPT,
                   manager.getAction("PAYMENT", "v2"));
    }
}
