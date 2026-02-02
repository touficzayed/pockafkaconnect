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
import java.util.HashMap;
import java.util.Map;

/**
 * Kafka Connect SMT for PAN (Primary Account Number) transformation.
 *
 * Supports three transformation strategies:
 * - REMOVE: Remove the encrypted PAN field completely
 * - DECRYPT: Decrypt JWE-encrypted PAN to plaintext
 * - REKEY: Decrypt PAN with our key, re-encrypt with partner's key
 */
public class PANTransformationSMT<R extends ConnectRecord<R>> implements Transformation<R> {

    private static final Logger log = LoggerFactory.getLogger(PANTransformationSMT.class);

    private static final String STRATEGY_CONFIG = "strategy";
    private static final String SOURCE_FIELD_CONFIG = "source.field";
    private static final String TARGET_FIELD_CONFIG = "target.field";
    private static final String PRIVATE_KEY_PATH_CONFIG = "private.key.path";
    private static final String PARTNER_KEYS_MAPPING_PATH_CONFIG = "partner.keys.mapping.path";
    private static final String INSTITUTION_ID_HEADER_CONFIG = "institution.id.header";

    public enum Strategy {
        REMOVE,
        DECRYPT,
        REKEY
    }

    private Strategy strategy;
    private String sourceField;
    private String targetField;
    private String institutionIdHeader;

    private JWEHandler jweHandler;
    private KeyStorageProvider keyStorageProvider;

    @Override
    public ConfigDef config() {
        return new ConfigDef()
            .define(STRATEGY_CONFIG, ConfigDef.Type.STRING, ConfigDef.NO_DEFAULT_VALUE,
                    ConfigDef.Importance.HIGH, "Transformation strategy: REMOVE, DECRYPT, or REKEY")
            .define(SOURCE_FIELD_CONFIG, ConfigDef.Type.STRING, "encryptedPrimaryAccountNumber",
                    ConfigDef.Importance.MEDIUM, "Field name containing encrypted PAN")
            .define(TARGET_FIELD_CONFIG, ConfigDef.Type.STRING, "primaryAccountNumber",
                    ConfigDef.Importance.MEDIUM, "Field name for transformed PAN")
            .define(PRIVATE_KEY_PATH_CONFIG, ConfigDef.Type.STRING, null,
                    ConfigDef.Importance.HIGH, "Path to private RSA key for decryption")
            .define(PARTNER_KEYS_MAPPING_PATH_CONFIG, ConfigDef.Type.STRING, null,
                    ConfigDef.Importance.MEDIUM, "Path to partner keys mapping JSON file")
            .define(INSTITUTION_ID_HEADER_CONFIG, ConfigDef.Type.STRING, "X-Institution-Id",
                    ConfigDef.Importance.MEDIUM, "Header name containing institution ID");
    }

    @Override
    public void configure(Map<String, ?> configs) {
        final SimpleConfig config = new SimpleConfig(config(), configs);

        String strategyStr = config.getString(STRATEGY_CONFIG);
        try {
            this.strategy = Strategy.valueOf(strategyStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new DataException("Invalid strategy: " + strategyStr);
        }

        this.sourceField = config.getString(SOURCE_FIELD_CONFIG);
        this.targetField = config.getString(TARGET_FIELD_CONFIG);
        this.institutionIdHeader = config.getString(INSTITUTION_ID_HEADER_CONFIG);

        String privateKeyPath = config.getString(PRIVATE_KEY_PATH_CONFIG);
        String partnerKeysMappingPath = config.getString(PARTNER_KEYS_MAPPING_PATH_CONFIG);

        // Validate and initialize based on strategy
        if (strategy == Strategy.DECRYPT || strategy == Strategy.REKEY) {
            if (privateKeyPath == null || privateKeyPath.isEmpty()) {
                throw new DataException("private.key.path is required for " + strategy);
            }

            this.jweHandler = new JWEHandler();

            // Load partner keys mapping for REKEY
            Map<String, String> partnerKeysMapping = new HashMap<>();
            if (strategy == Strategy.REKEY) {
                if (partnerKeysMappingPath == null || partnerKeysMappingPath.isEmpty()) {
                    throw new DataException("partner.keys.mapping.path is required for REKEY");
                }
                partnerKeysMapping = loadPartnerKeysMapping(partnerKeysMappingPath);
            }

            this.keyStorageProvider = new FileKeyStorageProvider(privateKeyPath, partnerKeysMapping);
            log.info("PANTransformationSMT configured with strategy: {}", strategy);
        }
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

        switch (strategy) {
            case REMOVE:
                return applyRemove(record);
            case DECRYPT:
                return applyDecrypt(record);
            case REKEY:
                return applyRekey(record);
            default:
                throw new DataException("Unknown strategy: " + strategy);
        }
    }

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

    private R applyDecrypt(R record) {
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

    private R applyRekey(R record) {
        String institutionId = getInstitutionIdFromHeaders(record);
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

    private String getInstitutionIdFromHeaders(R record) {
        for (Header header : record.headers()) {
            if (header.key().equals(institutionIdHeader)) {
                Object value = header.value();
                return value != null ? value.toString() : null;
            }
        }
        return null;
    }

    @Override
    public void close() {
        // No resources to close
    }
}
