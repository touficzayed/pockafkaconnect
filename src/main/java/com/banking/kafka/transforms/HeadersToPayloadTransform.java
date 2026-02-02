package com.banking.kafka.transforms;

import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.header.Header;
import org.apache.kafka.connect.transforms.Transformation;
import org.apache.kafka.connect.transforms.util.SimpleConfig;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Arrays;

/**
 * Kafka Connect Single Message Transform (SMT) that extracts Kafka headers
 * and adds them to the message payload.
 *
 * This transformation is designed for the Banking POC to preserve critical
 * metadata (institution ID, event type, version) from Kafka headers into
 * the stored JSONL files.
 *
 * Configuration:
 * - mandatory.headers: Comma-separated list of required headers
 * - optional.headers: Comma-separated list of optional headers
 * - target.field: Field name in payload to store headers (default: "headers")
 * - fail.on.missing.mandatory: Fail if mandatory header is missing (default: true)
 */
public class HeadersToPayloadTransform<R extends ConnectRecord<R>> implements Transformation<R> {

    private static final String MANDATORY_HEADERS_CONFIG = "mandatory.headers";
    private static final String OPTIONAL_HEADERS_CONFIG = "optional.headers";
    private static final String TARGET_FIELD_CONFIG = "target.field";
    private static final String FAIL_ON_MISSING_CONFIG = "fail.on.missing.mandatory";

    private static final String MANDATORY_HEADERS_DOC = "Comma-separated list of mandatory header names";
    private static final String OPTIONAL_HEADERS_DOC = "Comma-separated list of optional header names";
    private static final String TARGET_FIELD_DOC = "Name of the field to store headers in the payload";
    private static final String FAIL_ON_MISSING_DOC = "Whether to fail if a mandatory header is missing";

    private Set<String> mandatoryHeaders;
    private Set<String> optionalHeaders;
    private String targetField;
    private boolean failOnMissing;

    @Override
    public ConfigDef config() {
        return new ConfigDef()
            .define(MANDATORY_HEADERS_CONFIG,
                    ConfigDef.Type.STRING,
                    ConfigDef.NO_DEFAULT_VALUE,
                    ConfigDef.Importance.HIGH,
                    MANDATORY_HEADERS_DOC)
            .define(OPTIONAL_HEADERS_CONFIG,
                    ConfigDef.Type.STRING,
                    "",
                    ConfigDef.Importance.MEDIUM,
                    OPTIONAL_HEADERS_DOC)
            .define(TARGET_FIELD_CONFIG,
                    ConfigDef.Type.STRING,
                    "headers",
                    ConfigDef.Importance.LOW,
                    TARGET_FIELD_DOC)
            .define(FAIL_ON_MISSING_CONFIG,
                    ConfigDef.Type.BOOLEAN,
                    true,
                    ConfigDef.Importance.MEDIUM,
                    FAIL_ON_MISSING_DOC);
    }

    @Override
    public void configure(Map<String, ?> configs) {
        final SimpleConfig config = new SimpleConfig(config(), configs);

        String mandatoryHeadersStr = config.getString(MANDATORY_HEADERS_CONFIG);
        String optionalHeadersStr = config.getString(OPTIONAL_HEADERS_CONFIG);

        this.mandatoryHeaders = parseHeaderNames(mandatoryHeadersStr);
        this.optionalHeaders = parseHeaderNames(optionalHeadersStr);
        this.targetField = config.getString(TARGET_FIELD_CONFIG);
        this.failOnMissing = config.getBoolean(FAIL_ON_MISSING_CONFIG);
    }

    @Override
    public R apply(R record) {
        if (record.value() == null) {
            return record;
        }

        // Extract headers from the record
        Map<String, String> extractedHeaders = new HashMap<>();

        for (Header header : record.headers()) {
            String headerName = header.key();
            if (mandatoryHeaders.contains(headerName) || optionalHeaders.contains(headerName)) {
                Object headerValue = header.value();
                if (headerValue != null) {
                    extractedHeaders.put(headerName, headerValue.toString());
                }
            }
        }

        // Validate mandatory headers
        for (String mandatoryHeader : mandatoryHeaders) {
            if (!extractedHeaders.containsKey(mandatoryHeader)) {
                if (failOnMissing) {
                    throw new DataException("Mandatory header '" + mandatoryHeader + "' is missing");
                }
            }
        }

        // Transform the value based on schema type
        if (record.valueSchema() == null) {
            // Schema-less (Map)
            return applySchemaless(record, extractedHeaders);
        } else {
            // With schema (Struct)
            return applyWithSchema(record, extractedHeaders);
        }
    }

    @SuppressWarnings("unchecked")
    private R applySchemaless(R record, Map<String, String> headers) {
        final Map<String, Object> value = (Map<String, Object>) record.value();
        final Map<String, Object> updatedValue = new HashMap<>(value);
        updatedValue.put(targetField, headers);

        return record.newRecord(
            record.topic(),
            record.kafkaPartition(),
            record.keySchema(),
            record.key(),
            null, // no schema
            updatedValue,
            record.timestamp()
        );
    }

    private R applyWithSchema(R record, Map<String, String> headers) {
        final Struct value = (Struct) record.value();
        final Schema schema = record.valueSchema();

        // Build new schema with headers field
        final SchemaBuilder builder = SchemaBuilder.struct();
        for (org.apache.kafka.connect.data.Field field : schema.fields()) {
            builder.field(field.name(), field.schema());
        }
        builder.field(targetField, SchemaBuilder.map(Schema.STRING_SCHEMA, Schema.STRING_SCHEMA).build());
        final Schema newSchema = builder.build();

        // Build new struct with headers
        final Struct newValue = new Struct(newSchema);
        for (org.apache.kafka.connect.data.Field field : schema.fields()) {
            newValue.put(field.name(), value.get(field));
        }
        newValue.put(targetField, headers);

        return record.newRecord(
            record.topic(),
            record.kafkaPartition(),
            record.keySchema(),
            record.key(),
            newSchema,
            newValue,
            record.timestamp()
        );
    }

    @Override
    public void close() {
        // No resources to close
    }

    /**
     * Parse comma-separated header names and return as a Set
     */
    private Set<String> parseHeaderNames(String headerNamesStr) {
        if (headerNamesStr == null || headerNamesStr.trim().isEmpty()) {
            return Set.of();
        }
        return Arrays.stream(headerNamesStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }
}
