package com.banking.kafka.transforms;

import com.banking.kafka.crypto.FileKeyStorageProvider;
import com.banking.kafka.crypto.JWEHandler;
import com.banking.kafka.crypto.KeyStorageProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.header.Header;
import org.apache.kafka.connect.transforms.Transformation;
import org.apache.kafka.connect.transforms.util.SimpleConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Kafka Connect SMT for PAN (Primary Account Number) transformation.
 *
 * Supports rule-based transformation strategies based on headers:
 * - Institution ID (X-Institution-Id)
 * - Event Type (X-Event-Type)
 * - Version (X-Version)
 *
 * Strategies:
 * - REMOVE: Remove the encrypted PAN field completely
 * - MASK: Mask PAN showing only last 4 digits (e.g., ****1234)
 * - DECRYPT: Decrypt JWE-encrypted PAN to plaintext
 * - REKEY: Decrypt PAN with our key, re-encrypt with partner's key
 *
 * Configuration example:
 * transforms.transformPAN.default.strategy=REMOVE
 * transforms.transformPAN.rules=BNK001:PAYMENT:*:MASK,BNK002:*:*:REMOVE
 *
 * Rule format: Institution:EventType:Version:Strategy (* = wildcard)
 */
public class PANTransformationSMT<R extends ConnectRecord<R>> implements Transformation<R> {

    private static final Logger log = LoggerFactory.getLogger(PANTransformationSMT.class);

    // Config keys
    private static final String STRATEGY_CONFIG = "strategy";
    private static final String DEFAULT_STRATEGY_CONFIG = "default.strategy";
    private static final String RULES_CONFIG = "rules";
    private static final String SOURCE_FIELD_CONFIG = "source.field";
    private static final String TARGET_FIELD_CONFIG = "target.field";
    private static final String PRIVATE_KEY_PATH_CONFIG = "private.key.path";
    private static final String PARTNER_KEYS_MAPPING_PATH_CONFIG = "partner.keys.mapping.path";
    private static final String INSTITUTION_ID_HEADER_CONFIG = "institution.id.header";
    private static final String EVENT_TYPE_HEADER_CONFIG = "event.type.header";
    private static final String VERSION_HEADER_CONFIG = "version.header";
    private static final String MASK_CHARACTER_CONFIG = "mask.character";
    private static final String MASK_VISIBLE_DIGITS_CONFIG = "mask.visible.digits";

    private static final String WILDCARD = "*";

    public enum Strategy {
        REMOVE,
        MASK,
        DECRYPT,
        REKEY
    }

    /**
     * Rule for matching headers to a strategy
     */
    private static class TransformRule {
        final String institution;
        final String eventType;
        final String version;
        final Strategy strategy;

        TransformRule(String institution, String eventType, String version, Strategy strategy) {
            this.institution = institution;
            this.eventType = eventType;
            this.version = version;
            this.strategy = strategy;
        }

        boolean matches(String inst, String event, String ver) {
            return (WILDCARD.equals(institution) || institution.equalsIgnoreCase(inst))
                && (WILDCARD.equals(eventType) || eventType.equalsIgnoreCase(event))
                && (WILDCARD.equals(version) || version.equalsIgnoreCase(ver));
        }

        @Override
        public String toString() {
            return institution + ":" + eventType + ":" + version + ":" + strategy;
        }
    }

    private Strategy defaultStrategy;
    private List<TransformRule> rules;
    private String sourceField;
    private String targetField;
    private String institutionIdHeader;
    private String eventTypeHeader;
    private String versionHeader;
    private char maskCharacter;
    private int maskVisibleDigits;

    private JWEHandler jweHandler;
    private KeyStorageProvider keyStorageProvider;

    @Override
    public ConfigDef config() {
        return new ConfigDef()
            .define(STRATEGY_CONFIG, ConfigDef.Type.STRING, null,
                    ConfigDef.Importance.MEDIUM, "Single transformation strategy (legacy): REMOVE, MASK, DECRYPT, or REKEY")
            .define(DEFAULT_STRATEGY_CONFIG, ConfigDef.Type.STRING, "REMOVE",
                    ConfigDef.Importance.MEDIUM, "Default strategy when no rule matches")
            .define(RULES_CONFIG, ConfigDef.Type.STRING, "",
                    ConfigDef.Importance.HIGH, "Comma-separated rules: Institution:EventType:Version:Strategy (* = wildcard)")
            .define(SOURCE_FIELD_CONFIG, ConfigDef.Type.STRING, "encryptedPrimaryAccountNumber",
                    ConfigDef.Importance.MEDIUM, "Field name containing encrypted PAN")
            .define(TARGET_FIELD_CONFIG, ConfigDef.Type.STRING, "primaryAccountNumber",
                    ConfigDef.Importance.MEDIUM, "Field name for transformed PAN (DECRYPT/REKEY)")
            .define(PRIVATE_KEY_PATH_CONFIG, ConfigDef.Type.STRING, null,
                    ConfigDef.Importance.MEDIUM, "Path to private RSA key for decryption")
            .define(PARTNER_KEYS_MAPPING_PATH_CONFIG, ConfigDef.Type.STRING, null,
                    ConfigDef.Importance.MEDIUM, "Path to partner keys mapping JSON file")
            .define(INSTITUTION_ID_HEADER_CONFIG, ConfigDef.Type.STRING, "X-Institution-Id",
                    ConfigDef.Importance.MEDIUM, "Header name for institution ID")
            .define(EVENT_TYPE_HEADER_CONFIG, ConfigDef.Type.STRING, "X-Event-Type",
                    ConfigDef.Importance.MEDIUM, "Header name for event type")
            .define(VERSION_HEADER_CONFIG, ConfigDef.Type.STRING, "X-Version",
                    ConfigDef.Importance.MEDIUM, "Header name for version")
            .define(MASK_CHARACTER_CONFIG, ConfigDef.Type.STRING, "*",
                    ConfigDef.Importance.LOW, "Character used for masking")
            .define(MASK_VISIBLE_DIGITS_CONFIG, ConfigDef.Type.INT, 4,
                    ConfigDef.Importance.LOW, "Number of visible digits at the end when masking");
    }

    @Override
    public void configure(Map<String, ?> configs) {
        final SimpleConfig config = new SimpleConfig(config(), configs);

        this.sourceField = config.getString(SOURCE_FIELD_CONFIG);
        this.targetField = config.getString(TARGET_FIELD_CONFIG);
        this.institutionIdHeader = config.getString(INSTITUTION_ID_HEADER_CONFIG);
        this.eventTypeHeader = config.getString(EVENT_TYPE_HEADER_CONFIG);
        this.versionHeader = config.getString(VERSION_HEADER_CONFIG);
        this.maskCharacter = config.getString(MASK_CHARACTER_CONFIG).charAt(0);
        this.maskVisibleDigits = config.getInt(MASK_VISIBLE_DIGITS_CONFIG);

        // Parse default strategy
        String defaultStrategyStr = config.getString(DEFAULT_STRATEGY_CONFIG);
        try {
            this.defaultStrategy = Strategy.valueOf(defaultStrategyStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new DataException("Invalid default strategy: " + defaultStrategyStr);
        }

        // Check for legacy single strategy config
        String legacyStrategy = config.getString(STRATEGY_CONFIG);
        if (legacyStrategy != null && !legacyStrategy.isEmpty()) {
            try {
                this.defaultStrategy = Strategy.valueOf(legacyStrategy.toUpperCase());
                log.info("Using legacy single strategy: {}", defaultStrategy);
            } catch (IllegalArgumentException e) {
                throw new DataException("Invalid strategy: " + legacyStrategy);
            }
        }

        // Parse rules
        this.rules = parseRules(config.getString(RULES_CONFIG));

        // Initialize crypto if needed
        boolean needsCrypto = defaultStrategy == Strategy.DECRYPT || defaultStrategy == Strategy.REKEY;
        for (TransformRule rule : rules) {
            if (rule.strategy == Strategy.DECRYPT || rule.strategy == Strategy.REKEY) {
                needsCrypto = true;
                break;
            }
        }

        if (needsCrypto) {
            String privateKeyPath = config.getString(PRIVATE_KEY_PATH_CONFIG);
            String partnerKeysMappingPath = config.getString(PARTNER_KEYS_MAPPING_PATH_CONFIG);

            if (privateKeyPath == null || privateKeyPath.isEmpty()) {
                log.warn("private.key.path not configured - DECRYPT/REKEY strategies will fail at runtime");
            } else {
                this.jweHandler = new JWEHandler();
                Map<String, String> partnerKeysMapping = new HashMap<>();
                if (partnerKeysMappingPath != null && !partnerKeysMappingPath.isEmpty()) {
                    partnerKeysMapping = loadPartnerKeysMapping(partnerKeysMappingPath);
                }
                this.keyStorageProvider = new FileKeyStorageProvider(privateKeyPath, partnerKeysMapping);
            }
        }

        log.info("PANTransformationSMT configured: defaultStrategy={}, rules={}", defaultStrategy, rules);
    }

    private List<TransformRule> parseRules(String rulesStr) {
        List<TransformRule> result = new ArrayList<>();
        if (rulesStr == null || rulesStr.trim().isEmpty()) {
            return result;
        }

        String[] ruleStrings = rulesStr.split(",");
        for (String ruleStr : ruleStrings) {
            String trimmed = ruleStr.trim();
            if (trimmed.isEmpty()) continue;

            String[] parts = trimmed.split(":");
            if (parts.length != 4) {
                throw new DataException("Invalid rule format (expected Institution:EventType:Version:Strategy): " + trimmed);
            }

            String institution = parts[0].trim();
            String eventType = parts[1].trim();
            String version = parts[2].trim();
            String strategyStr = parts[3].trim();

            Strategy strategy;
            try {
                strategy = Strategy.valueOf(strategyStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new DataException("Invalid strategy in rule: " + strategyStr);
            }

            result.add(new TransformRule(institution, eventType, version, strategy));
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> loadPartnerKeysMapping(String filePath) {
        try {
            File file = new File(filePath);
            if (!file.exists()) {
                throw new DataException("Partner keys mapping file not found: " + filePath);
            }

            String json = new String(Files.readAllBytes(file.toPath()));
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new DataException("Failed to load partner keys mapping from " + filePath, e);
        }
    }

    @Override
    public R apply(R record) {
        if (record.value() == null) {
            return record;
        }

        // Get headers
        String institution = getHeaderValue(record, institutionIdHeader);
        String eventType = getHeaderValue(record, eventTypeHeader);
        String version = getHeaderValue(record, versionHeader);

        // Find matching rule
        Strategy strategy = findMatchingStrategy(institution, eventType, version);

        log.debug("Applying strategy {} for institution={}, eventType={}, version={}",
                strategy, institution, eventType, version);

        switch (strategy) {
            case REMOVE:
                return applyRemove(record);
            case MASK:
                return applyMask(record);
            case DECRYPT:
                return applyDecrypt(record);
            case REKEY:
                return applyRekey(record, institution);
            default:
                throw new DataException("Unknown strategy: " + strategy);
        }
    }

    private Strategy findMatchingStrategy(String institution, String eventType, String version) {
        for (TransformRule rule : rules) {
            if (rule.matches(institution, eventType, version)) {
                log.debug("Matched rule: {}", rule);
                return rule.strategy;
            }
        }
        return defaultStrategy;
    }

    private String getHeaderValue(R record, String headerName) {
        for (Header header : record.headers()) {
            if (header.key().equals(headerName)) {
                Object value = header.value();
                return value != null ? value.toString() : null;
            }
        }
        return null;
    }

    // ==================== REMOVE Strategy ====================

    private R applyRemove(R record) {
        if (record.valueSchema() == null) {
            return applyRemoveSchemaless(record);
        } else {
            return applyRemoveWithSchema(record);
        }
    }

    @SuppressWarnings("unchecked")
    private R applyRemoveSchemaless(R record) {
        final Map<String, Object> value = (Map<String, Object>) record.value();
        if (!value.containsKey(sourceField)) {
            return record;
        }

        final Map<String, Object> updatedValue = new HashMap<>(value);
        updatedValue.remove(sourceField);

        return record.newRecord(record.topic(), record.kafkaPartition(), record.keySchema(),
                record.key(), null, updatedValue, record.timestamp(), record.headers());
    }

    private R applyRemoveWithSchema(R record) {
        final Struct value = (Struct) record.value();
        final Schema schema = record.valueSchema();

        if (schema.field(sourceField) == null) {
            return record;
        }

        final SchemaBuilder builder = SchemaBuilder.struct();
        for (org.apache.kafka.connect.data.Field field : schema.fields()) {
            if (!field.name().equals(sourceField)) {
                builder.field(field.name(), field.schema());
            }
        }
        final Schema newSchema = builder.build();

        final Struct newValue = new Struct(newSchema);
        for (org.apache.kafka.connect.data.Field field : schema.fields()) {
            if (!field.name().equals(sourceField)) {
                newValue.put(field.name(), value.get(field));
            }
        }

        return record.newRecord(record.topic(), record.kafkaPartition(), record.keySchema(),
                record.key(), newSchema, newValue, record.timestamp(), record.headers());
    }

    // ==================== MASK Strategy ====================

    private R applyMask(R record) {
        if (record.valueSchema() == null) {
            return applyMaskSchemaless(record);
        } else {
            return applyMaskWithSchema(record);
        }
    }

    @SuppressWarnings("unchecked")
    private R applyMaskSchemaless(R record) {
        final Map<String, Object> value = (Map<String, Object>) record.value();
        if (!value.containsKey(sourceField)) {
            return record;
        }

        String originalValue = (String) value.get(sourceField);
        String maskedValue = maskValue(originalValue);

        final Map<String, Object> updatedValue = new HashMap<>(value);
        updatedValue.remove(sourceField);
        updatedValue.put(targetField, maskedValue);

        return record.newRecord(record.topic(), record.kafkaPartition(), record.keySchema(),
                record.key(), null, updatedValue, record.timestamp(), record.headers());
    }

    private R applyMaskWithSchema(R record) {
        final Struct value = (Struct) record.value();
        final Schema schema = record.valueSchema();

        if (schema.field(sourceField) == null) {
            return record;
        }

        String originalValue = value.getString(sourceField);
        String maskedValue = maskValue(originalValue);

        final SchemaBuilder builder = SchemaBuilder.struct();
        for (org.apache.kafka.connect.data.Field field : schema.fields()) {
            if (!field.name().equals(sourceField)) {
                builder.field(field.name(), field.schema());
            }
        }
        builder.field(targetField, Schema.STRING_SCHEMA);
        final Schema newSchema = builder.build();

        final Struct newValue = new Struct(newSchema);
        for (org.apache.kafka.connect.data.Field field : schema.fields()) {
            if (!field.name().equals(sourceField)) {
                newValue.put(field.name(), value.get(field));
            }
        }
        newValue.put(targetField, maskedValue);

        return record.newRecord(record.topic(), record.kafkaPartition(), record.keySchema(),
                record.key(), newSchema, newValue, record.timestamp(), record.headers());
    }

    private String maskValue(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }

        // If the value is a JWE token, we can't mask the actual PAN without decrypting
        // In this case, we'll just return a placeholder
        if (value.contains(".") && value.split("\\.").length == 5) {
            // Looks like a JWE token (5 parts separated by dots)
            return String.valueOf(maskCharacter).repeat(12) + "****";
        }

        int len = value.length();
        if (len <= maskVisibleDigits) {
            return value;
        }

        StringBuilder masked = new StringBuilder();
        for (int i = 0; i < len - maskVisibleDigits; i++) {
            masked.append(maskCharacter);
        }
        masked.append(value.substring(len - maskVisibleDigits));
        return masked.toString();
    }

    // ==================== DECRYPT Strategy ====================

    private R applyDecrypt(R record) {
        if (jweHandler == null || keyStorageProvider == null) {
            throw new DataException("DECRYPT strategy requires private.key.path configuration");
        }

        if (record.valueSchema() == null) {
            return applyDecryptSchemaless(record);
        } else {
            return applyDecryptWithSchema(record);
        }
    }

    @SuppressWarnings("unchecked")
    private R applyDecryptSchemaless(R record) {
        final Map<String, Object> value = (Map<String, Object>) record.value();
        if (!value.containsKey(sourceField)) {
            return record;
        }

        String encryptedPAN = (String) value.get(sourceField);
        try {
            RSAPrivateKey privateKey = keyStorageProvider.getPrivateKey(null);
            String decryptedPAN = jweHandler.decrypt(encryptedPAN, privateKey);

            final Map<String, Object> updatedValue = new HashMap<>(value);
            updatedValue.remove(sourceField);
            updatedValue.put(targetField, decryptedPAN);

            return record.newRecord(record.topic(), record.kafkaPartition(), record.keySchema(),
                    record.key(), null, updatedValue, record.timestamp(), record.headers());
        } catch (Exception e) {
            throw new DataException("Failed to decrypt PAN", e);
        }
    }

    private R applyDecryptWithSchema(R record) {
        final Struct value = (Struct) record.value();
        final Schema schema = record.valueSchema();

        if (schema.field(sourceField) == null) {
            return record;
        }

        String encryptedPAN = value.getString(sourceField);
        try {
            RSAPrivateKey privateKey = keyStorageProvider.getPrivateKey(null);
            String decryptedPAN = jweHandler.decrypt(encryptedPAN, privateKey);

            final SchemaBuilder builder = SchemaBuilder.struct();
            for (org.apache.kafka.connect.data.Field field : schema.fields()) {
                if (!field.name().equals(sourceField)) {
                    builder.field(field.name(), field.schema());
                }
            }
            builder.field(targetField, Schema.STRING_SCHEMA);
            final Schema newSchema = builder.build();

            final Struct newValue = new Struct(newSchema);
            for (org.apache.kafka.connect.data.Field field : schema.fields()) {
                if (!field.name().equals(sourceField)) {
                    newValue.put(field.name(), value.get(field));
                }
            }
            newValue.put(targetField, decryptedPAN);

            return record.newRecord(record.topic(), record.kafkaPartition(), record.keySchema(),
                    record.key(), newSchema, newValue, record.timestamp(), record.headers());
        } catch (Exception e) {
            throw new DataException("Failed to decrypt PAN", e);
        }
    }

    // ==================== REKEY Strategy ====================

    private R applyRekey(R record, String institutionId) {
        if (jweHandler == null || keyStorageProvider == null) {
            throw new DataException("REKEY strategy requires private.key.path configuration");
        }

        if (institutionId == null) {
            throw new DataException("Institution ID header not found: " + institutionIdHeader);
        }

        if (record.valueSchema() == null) {
            return applyRekeySchemaless(record, institutionId);
        } else {
            return applyRekeyWithSchema(record, institutionId);
        }
    }

    @SuppressWarnings("unchecked")
    private R applyRekeySchemaless(R record, String institutionId) {
        final Map<String, Object> value = (Map<String, Object>) record.value();
        if (!value.containsKey(sourceField)) {
            return record;
        }

        String encryptedPAN = (String) value.get(sourceField);
        try {
            RSAPrivateKey privateKey = keyStorageProvider.getPrivateKey(null);
            RSAPublicKey partnerPublicKey = keyStorageProvider.getPublicKey(institutionId);
            String rekeyedPAN = jweHandler.rekey(encryptedPAN, privateKey, partnerPublicKey);

            final Map<String, Object> updatedValue = new HashMap<>(value);
            updatedValue.remove(sourceField);
            updatedValue.put(targetField, rekeyedPAN);

            return record.newRecord(record.topic(), record.kafkaPartition(), record.keySchema(),
                    record.key(), null, updatedValue, record.timestamp(), record.headers());
        } catch (Exception e) {
            throw new DataException("Failed to rekey PAN for institution: " + institutionId, e);
        }
    }

    private R applyRekeyWithSchema(R record, String institutionId) {
        final Struct value = (Struct) record.value();
        final Schema schema = record.valueSchema();

        if (schema.field(sourceField) == null) {
            return record;
        }

        String encryptedPAN = value.getString(sourceField);
        try {
            RSAPrivateKey privateKey = keyStorageProvider.getPrivateKey(null);
            RSAPublicKey partnerPublicKey = keyStorageProvider.getPublicKey(institutionId);
            String rekeyedPAN = jweHandler.rekey(encryptedPAN, privateKey, partnerPublicKey);

            final SchemaBuilder builder = SchemaBuilder.struct();
            for (org.apache.kafka.connect.data.Field field : schema.fields()) {
                if (!field.name().equals(sourceField)) {
                    builder.field(field.name(), field.schema());
                }
            }
            builder.field(targetField, Schema.STRING_SCHEMA);
            final Schema newSchema = builder.build();

            final Struct newValue = new Struct(newSchema);
            for (org.apache.kafka.connect.data.Field field : schema.fields()) {
                if (!field.name().equals(sourceField)) {
                    newValue.put(field.name(), value.get(field));
                }
            }
            newValue.put(targetField, rekeyedPAN);

            return record.newRecord(record.topic(), record.kafkaPartition(), record.keySchema(),
                    record.key(), newSchema, newValue, record.timestamp(), record.headers());
        } catch (Exception e) {
            throw new DataException("Failed to rekey PAN for institution: " + institutionId, e);
        }
    }

    @Override
    public void close() {
        // No resources to close
    }
}
