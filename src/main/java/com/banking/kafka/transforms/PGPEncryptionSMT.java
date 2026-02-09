package com.banking.kafka.transforms;

import com.banking.kafka.config.BankConfigManager;
import com.banking.kafka.crypto.PGPEncryptionHandler;
import com.banking.kafka.crypto.PGPOutputStreamWrapper;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.header.Header;
import org.apache.kafka.connect.transforms.Transformation;
import org.apache.kafka.connect.transforms.util.SimpleConfig;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Kafka Connect SMT for PGP streaming encryption.
 *
 * Encrypts the message payload based on rules defined in bank configuration.
 * Rules are evaluated by event type and version to determine whether to encrypt.
 *
 * Configuration example:
 * transforms.pgpEncryption.type=com.banking.kafka.transforms.PGPEncryptionSMT
 * transforms.pgpEncryption.config.file=/config/banks/bank-config.json
 * transforms.pgpEncryption.institution.header=X-Institution-Id
 * transforms.pgpEncryption.event.type.header=X-Event-Type
 * transforms.pgpEncryption.event.version.header=X-Event-Version
 *
 * Bank configuration example (in bank-config.json):
 * {
 *   "banks": {
 *     "BNK001": {
 *       "pgp_encryption": {
 *         "enabled": true,
 *         "public_key_path": "/keys/pgp/bnk001-public.asc",
 *         "armor": true,
 *         "rules": "PAYMENT:*:ENCRYPT,REFUND:v1:SKIP,*:*:ENCRYPT"
 *       }
 *     }
 *   }
 * }
 *
 * Rule format: EventType:Version:Action
 * - EventType: PAYMENT, REFUND, etc. (or * for all)
 * - Version: v1, v2, 1.0, etc. (or * for all)
 * - Action: ENCRYPT or SKIP
 */
public class PGPEncryptionSMT<R extends ConnectRecord<R>> implements Transformation<R> {

    private static final Logger log = LoggerFactory.getLogger(PGPEncryptionSMT.class);

    // Config keys
    private static final String CONFIG_FILE_CONFIG = "config.file";
    private static final String INSTITUTION_HEADER_CONFIG = "institution.header";
    private static final String EVENT_TYPE_HEADER_CONFIG = "event.type.header";
    private static final String EVENT_VERSION_HEADER_CONFIG = "event.version.header";

    private BankConfigManager bankConfigManager;
    private PGPEncryptionHandler pgpHandler;
    private String institutionHeader;
    private String eventTypeHeader;
    private String eventVersionHeader;

    @Override
    public ConfigDef config() {
        return new ConfigDef()
            .define(CONFIG_FILE_CONFIG, ConfigDef.Type.STRING, "/config/banks/bank-config.json",
                    ConfigDef.Importance.HIGH, "Path to bank configuration file")
            .define(INSTITUTION_HEADER_CONFIG, ConfigDef.Type.STRING, "X-Institution-Id",
                    ConfigDef.Importance.MEDIUM, "Header name for institution ID")
            .define(EVENT_TYPE_HEADER_CONFIG, ConfigDef.Type.STRING, "X-Event-Type",
                    ConfigDef.Importance.MEDIUM, "Header name for event type")
            .define(EVENT_VERSION_HEADER_CONFIG, ConfigDef.Type.STRING, "X-Event-Version",
                    ConfigDef.Importance.MEDIUM, "Header name for event version");
    }

    @Override
    public void configure(Map<String, ?> configs) {
        final SimpleConfig config = new SimpleConfig(config(), configs);

        this.institutionHeader = config.getString(INSTITUTION_HEADER_CONFIG);
        this.eventTypeHeader = config.getString(EVENT_TYPE_HEADER_CONFIG);
        this.eventVersionHeader = config.getString(EVENT_VERSION_HEADER_CONFIG);

        String configFilePath = config.getString(CONFIG_FILE_CONFIG);

        // Initialize bank config manager
        this.bankConfigManager = new BankConfigManager();
        try {
            bankConfigManager.loadConfig(configFilePath);
            log.info("Loaded bank configuration from: {}", configFilePath);
        } catch (IOException e) {
            throw new DataException("Failed to load bank configuration from " + configFilePath, e);
        }

        // Initialize PGP handler
        this.pgpHandler = new PGPEncryptionHandler();

        log.info("PGPEncryptionSMT configured: institutionHeader={}, eventTypeHeader={}, eventVersionHeader={}",
            institutionHeader, eventTypeHeader, eventVersionHeader);
    }

    @Override
    public R apply(R record) {
        if (record.value() == null) {
            return record;
        }

        // Extract headers
        String institution = getHeaderValue(record, institutionHeader);
        String eventType = getHeaderValue(record, eventTypeHeader);
        String eventVersion = getHeaderValue(record, eventVersionHeader);

        log.debug("Processing PGP encryption for institution={}, eventType={}, version={}",
            institution, eventType, eventVersion);

        // Get bank configuration
        BankConfigManager.BankConfig bankConfig = bankConfigManager.getConfig(institution);
        BankConfigManager.PGPConfig pgpConfig = bankConfig.getPgpConfig();

        // Check if encryption is enabled and applicable
        if (!pgpConfig.isEnabled()) {
            log.debug("PGP encryption disabled for institution: {}", institution);
            return record;
        }

        // Check if this event matches the encryption rules
        if (!shouldEncryptEvent(pgpConfig, eventType, eventVersion)) {
            log.debug("Event {}/{} does not match encryption rules for {}, skipping",
                eventType, eventVersion, institution);
            return record;
        }

        // Encrypt the payload
        try {
            String encryptedValue = encryptPayload(record.value().toString(),
                pgpConfig, institution);

            // Return new record with encrypted value, preserving headers and key
            return record.newRecord(
                record.topic(),
                record.kafkaPartition(),
                record.keySchema(),
                record.key(),
                record.valueSchema(),
                encryptedValue,
                record.timestamp()
            );
        } catch (Exception e) {
            log.error("Failed to encrypt payload for institution={}, eventType={}, version={}",
                institution, eventType, eventVersion, e);
            throw new DataException("PGP encryption failed for institution: " + institution, e);
        }
    }

    /**
     * Check if an event should be encrypted based on rules.
     */
    private boolean shouldEncryptEvent(BankConfigManager.PGPConfig pgpConfig,
                                       String eventType, String eventVersion) {
        String rules = pgpConfig.getRules();

        if (rules == null || rules.trim().isEmpty()) {
            // No rules defined - encrypt by default if enabled
            return true;
        }

        PGPEncryptionRuleManager ruleManager = new PGPEncryptionRuleManager(
            rules,
            PGPEncryptionRuleManager.Action.ENCRYPT  // Default to encrypt
        );

        return ruleManager.shouldEncrypt(eventType, eventVersion);
    }

    /**
     * Encrypt payload using streaming PGP encryption.
     */
    private String encryptPayload(String plaintext, BankConfigManager.PGPConfig pgpConfig,
                                  String institution) throws Exception {
        String publicKeyPath = pgpConfig.getPublicKeyPath();

        if (publicKeyPath == null || publicKeyPath.isEmpty()) {
            throw new DataException("No public key path configured for institution: " + institution);
        }

        File keyFile = new File(publicKeyPath);
        if (!keyFile.exists()) {
            throw new DataException("Public key file not found: " + publicKeyPath);
        }

        try {
            // Load public key from file
            PGPPublicKey publicKey = pgpHandler.loadPublicKey(publicKeyPath);

            // Use streaming encryption for memory efficiency
            ByteArrayOutputStream encryptedOutput = new ByteArrayOutputStream();

            // Create streaming encryptor and write plaintext
            try (PGPOutputStreamWrapper pgpOut = new PGPOutputStreamWrapper(
                    encryptedOutput, publicKey, pgpConfig.isArmor())) {
                byte[] plaintextBytes = plaintext.getBytes(StandardCharsets.UTF_8);
                pgpOut.write(plaintextBytes);
            }

            // Get encrypted data
            byte[] encrypted = encryptedOutput.toByteArray();

            // Return as string
            if (pgpConfig.isArmor()) {
                // Armored format is ASCII, convert directly
                return new String(encrypted, StandardCharsets.UTF_8);
            } else {
                // Binary format, encode to Base64 for safe JSON representation
                return java.util.Base64.getEncoder().encodeToString(encrypted);
            }
        } catch (PGPEncryptionHandler.PGPException e) {
            throw new DataException("PGP error encrypting payload for institution: " + institution, e);
        }
    }

    /**
     * Extract header value from record.
     */
    private String getHeaderValue(R record, String headerName) {
        if (record.headers() == null) {
            return null;
        }

        try {
            for (Header header : record.headers()) {
                if (header.key().equals(headerName)) {
                    Object headerValue = header.value();
                    if (headerValue != null) {
                        if (headerValue instanceof byte[]) {
                            return new String((byte[]) headerValue, StandardCharsets.UTF_8);
                        } else {
                            return headerValue.toString();
                        }
                    }
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("Failed to extract header {}", headerName, e);
            return null;
        }
    }

    @Override
    public void close() {
        // No resources to close
    }
}
