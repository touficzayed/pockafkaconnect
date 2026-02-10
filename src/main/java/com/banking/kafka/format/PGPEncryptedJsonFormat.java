package com.banking.kafka.format;

import com.banking.kafka.config.BankConfigManager;
import com.banking.kafka.crypto.PGPEncryptionHandler;
import io.confluent.connect.s3.S3SinkConnectorConfig;
import io.confluent.connect.s3.format.json.JsonFormat;
import io.confluent.connect.s3.storage.S3Storage;
import io.confluent.connect.storage.format.RecordWriter;
import io.confluent.connect.storage.format.RecordWriterProvider;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.sink.SinkRecord;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom S3 Format that encrypts JSON data with PGP at write time.
 *
 * This format encrypts data AFTER partitioning, solving the issue where
 * PGP encryption as an SMT breaks field-based partitioning.
 *
 * Configuration:
 * - format.class=com.banking.kafka.format.PGPEncryptedJsonFormat
 * - format.pgp.config.file=/config/banks/bank-config.json
 * - format.pgp.institution.header=X-Institution-Id
 * - format.pgp.event.type.header=X-Event-Type
 * - format.pgp.event.version.header=X-Event-Version
 */
public class PGPEncryptedJsonFormat extends JsonFormat {

    private static final Logger log = LoggerFactory.getLogger(PGPEncryptedJsonFormat.class);

    // Config keys
    public static final String CONFIG_FILE = "format.pgp.config.file";
    public static final String INSTITUTION_HEADER = "format.pgp.institution.header";
    public static final String EVENT_TYPE_HEADER = "format.pgp.event.type.header";
    public static final String EVENT_VERSION_HEADER = "format.pgp.event.version.header";

    // Default values
    private static final String DEFAULT_CONFIG_FILE = "/config/banks/bank-config.json";
    private static final String DEFAULT_INSTITUTION_HEADER = "X-Institution-Id";
    private static final String DEFAULT_EVENT_TYPE_HEADER = "X-Event-Type";
    private static final String DEFAULT_EVENT_VERSION_HEADER = "X-Event-Version";

    private final S3Storage storage;
    private final S3SinkConnectorConfig config;
    private final BankConfigManager bankConfigManager;
    private final PGPEncryptionHandler pgpHandler;
    private final String institutionHeader;
    private final String eventTypeHeader;
    private final String eventVersionHeader;

    // Cache for loaded public keys
    private final Map<String, PGPPublicKey> keyCache = new ConcurrentHashMap<>();

    public PGPEncryptedJsonFormat(S3Storage storage) {
        super(storage);
        this.storage = storage;
        this.config = storage.conf();
        this.pgpHandler = new PGPEncryptionHandler();

        // Get configuration from storage config
        Map<String, String> originals = config.originalsStrings();

        String configFile = originals.getOrDefault(CONFIG_FILE, DEFAULT_CONFIG_FILE);
        this.institutionHeader = originals.getOrDefault(INSTITUTION_HEADER, DEFAULT_INSTITUTION_HEADER);
        this.eventTypeHeader = originals.getOrDefault(EVENT_TYPE_HEADER, DEFAULT_EVENT_TYPE_HEADER);
        this.eventVersionHeader = originals.getOrDefault(EVENT_VERSION_HEADER, DEFAULT_EVENT_VERSION_HEADER);

        // Initialize bank config manager
        this.bankConfigManager = new BankConfigManager();
        try {
            bankConfigManager.loadConfig(configFile);
            log.info("PGPEncryptedJsonFormat: Loaded bank configuration from {}", configFile);
        } catch (IOException e) {
            throw new ConnectException("Failed to load bank configuration from " + configFile, e);
        }

        log.info("PGPEncryptedJsonFormat initialized: institutionHeader={}, eventTypeHeader={}, eventVersionHeader={}",
                institutionHeader, eventTypeHeader, eventVersionHeader);
    }

    @Override
    public RecordWriterProvider<S3SinkConnectorConfig> getRecordWriterProvider() {
        return new PGPEncryptedJsonRecordWriterProvider(super.getRecordWriterProvider());
    }

    /**
     * RecordWriterProvider that wraps JSON records with PGP encryption.
     */
    private class PGPEncryptedJsonRecordWriterProvider implements RecordWriterProvider<S3SinkConnectorConfig> {

        private final RecordWriterProvider<S3SinkConnectorConfig> delegate;

        public PGPEncryptedJsonRecordWriterProvider(RecordWriterProvider<S3SinkConnectorConfig> delegate) {
            this.delegate = delegate;
        }

        @Override
        public String getExtension() {
            return ".json.pgp";
        }

        @Override
        public RecordWriter getRecordWriter(S3SinkConnectorConfig conf, String filename) {
            return new PGPEncryptedJsonRecordWriter(conf, filename);
        }
    }

    /**
     * RecordWriter that buffers JSON records and encrypts them on commit.
     */
    private class PGPEncryptedJsonRecordWriter implements RecordWriter {

        private final S3SinkConnectorConfig conf;
        private final String filename;
        private final ByteArrayOutputStream buffer;
        private String currentInstitution;
        private String currentEventType;
        private String currentEventVersion;
        private boolean firstRecord = true;

        public PGPEncryptedJsonRecordWriter(S3SinkConnectorConfig conf, String filename) {
            this.conf = conf;
            this.filename = filename;
            this.buffer = new ByteArrayOutputStream();
        }

        @Override
        public void write(SinkRecord record) {
            try {
                // Extract institution and event info from first record
                if (firstRecord) {
                    currentInstitution = getHeaderValue(record, institutionHeader);
                    currentEventType = getHeaderValue(record, eventTypeHeader);
                    currentEventVersion = getHeaderValue(record, eventVersionHeader);
                    firstRecord = false;
                }

                // Convert record value to JSON
                String json = convertToJson(record);

                // Write JSON line
                if (buffer.size() > 0) {
                    buffer.write('\n');
                }
                buffer.write(json.getBytes(StandardCharsets.UTF_8));

            } catch (Exception e) {
                throw new ConnectException("Failed to write record", e);
            }
        }

        @Override
        public void close() {
            // Nothing to close for buffer
        }

        @Override
        public void commit() {
            try {
                byte[] data = buffer.toByteArray();

                if (data.length == 0) {
                    log.debug("No data to write for {}", filename);
                    return;
                }

                // Check if we should encrypt this data
                byte[] outputData;
                String outputFilename;

                if (shouldEncrypt(currentInstitution, currentEventType, currentEventVersion)) {
                    // Encrypt the data and add .json.pgp extension
                    outputData = encryptData(data, currentInstitution);
                    outputFilename = filename + ".json.pgp";
                    log.info("Encrypted {} bytes for institution {} (event: {}/{})",
                            data.length, currentInstitution, currentEventType, currentEventVersion);
                } else {
                    // Write unencrypted with .json extension
                    outputData = data;
                    outputFilename = filename + ".json";
                    log.debug("Writing {} bytes unencrypted for institution {} (event: {}/{})",
                            data.length, currentInstitution, currentEventType, currentEventVersion);
                }

                // Write to S3 using the storage API
                log.info("Writing {} bytes to S3: {}", outputData.length, outputFilename);
                OutputStream out = storage.create(outputFilename, conf, true);
                try {
                    out.write(outputData);
                    out.flush();
                    // Cast to S3OutputStream and commit if possible
                    if (out instanceof io.confluent.connect.s3.storage.S3OutputStream) {
                        io.confluent.connect.s3.storage.S3OutputStream s3Out =
                            (io.confluent.connect.s3.storage.S3OutputStream) out;
                        s3Out.commit();
                        log.info("Committed S3OutputStream for: {}", outputFilename);
                    }
                } finally {
                    out.close();
                }
                log.info("Successfully wrote to S3: {}", outputFilename);

            } catch (Exception e) {
                log.error("Failed to commit records to S3: {}", e.getMessage(), e);
                throw new ConnectException("Failed to commit records to S3", e);
            }
        }

        /**
         * Check if data should be encrypted based on bank configuration.
         */
        private boolean shouldEncrypt(String institution, String eventType, String eventVersion) {
            if (institution == null) {
                return false;
            }

            BankConfigManager.BankConfig bankConfig = bankConfigManager.getConfig(institution);
            BankConfigManager.PGPConfig pgpConfig = bankConfig.getPgpConfig();

            if (!pgpConfig.isEnabled()) {
                return false;
            }

            // Check rules
            String rules = pgpConfig.getRules();
            if (rules == null || rules.trim().isEmpty()) {
                return true; // Encrypt by default if enabled
            }

            // Parse rules (format: "PAYMENT:*:ENCRYPT,*:*:SKIP")
            for (String rule : rules.split(",")) {
                String[] parts = rule.trim().split(":");
                if (parts.length >= 3) {
                    String ruleEventType = parts[0].trim();
                    String ruleVersion = parts[1].trim();
                    String action = parts[2].trim();

                    boolean typeMatches = "*".equals(ruleEventType) ||
                            ruleEventType.equalsIgnoreCase(eventType);
                    boolean versionMatches = "*".equals(ruleVersion) ||
                            ruleVersion.equalsIgnoreCase(eventVersion);

                    if (typeMatches && versionMatches) {
                        return "ENCRYPT".equalsIgnoreCase(action);
                    }
                }
            }

            return false; // Default to no encryption if no rule matches
        }

        /**
         * Encrypt data using PGP for the given institution.
         */
        private byte[] encryptData(byte[] data, String institution) {
            try {
                BankConfigManager.BankConfig bankConfig = bankConfigManager.getConfig(institution);
                BankConfigManager.PGPConfig pgpConfig = bankConfig.getPgpConfig();

                String keyPath = pgpConfig.getPublicKeyPath();
                if (keyPath == null || keyPath.isEmpty()) {
                    throw new ConnectException("No public key configured for institution: " + institution);
                }

                // Load public key (with caching)
                PGPPublicKey publicKey = keyCache.computeIfAbsent(keyPath, path -> {
                    try {
                        return pgpHandler.loadPublicKey(path);
                    } catch (PGPEncryptionHandler.PGPException e) {
                        throw new ConnectException("Failed to load public key: " + path, e);
                    }
                });

                // Encrypt
                return pgpHandler.encrypt(data, publicKey, pgpConfig.isArmor());

            } catch (PGPEncryptionHandler.PGPException e) {
                throw new ConnectException("PGP encryption failed for institution: " + institution, e);
            }
        }

        /**
         * Convert a SinkRecord to JSON string.
         */
        private String convertToJson(SinkRecord record) {
            Object value = record.value();

            if (value == null) {
                return "null";
            }

            if (value instanceof String) {
                return (String) value;
            }

            if (value instanceof Map) {
                return mapToJson((Map<?, ?>) value);
            }

            if (value instanceof Struct) {
                return structToJson((Struct) value);
            }

            return value.toString();
        }

        /**
         * Convert a Map to JSON string.
         */
        private String mapToJson(Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;

            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first)
                    sb.append(",");
                first = false;

                sb.append("\"").append(escapeJson(entry.getKey().toString())).append("\":");
                sb.append(valueToJson(entry.getValue()));
            }

            sb.append("}");
            return sb.toString();
        }

        /**
         * Convert a Struct to JSON string.
         */
        private String structToJson(Struct struct) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;

            for (org.apache.kafka.connect.data.Field field : struct.schema().fields()) {
                if (!first)
                    sb.append(",");
                first = false;

                sb.append("\"").append(escapeJson(field.name())).append("\":");
                sb.append(valueToJson(struct.get(field)));
            }

            sb.append("}");
            return sb.toString();
        }

        /**
         * Convert a value to JSON representation.
         */
        private String valueToJson(Object value) {
            if (value == null) {
                return "null";
            }
            if (value instanceof String) {
                return "\"" + escapeJson((String) value) + "\"";
            }
            if (value instanceof Number || value instanceof Boolean) {
                return value.toString();
            }
            if (value instanceof Map) {
                return mapToJson((Map<?, ?>) value);
            }
            if (value instanceof Struct) {
                return structToJson((Struct) value);
            }
            if (value instanceof Iterable) {
                StringBuilder sb = new StringBuilder("[");
                boolean first = true;
                for (Object item : (Iterable<?>) value) {
                    if (!first)
                        sb.append(",");
                    first = false;
                    sb.append(valueToJson(item));
                }
                sb.append("]");
                return sb.toString();
            }
            return "\"" + escapeJson(value.toString()) + "\"";
        }

        /**
         * Escape special JSON characters.
         */
        private String escapeJson(String str) {
            return str.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
        }

        /**
         * Get header value from record.
         */
        private String getHeaderValue(SinkRecord record, String headerName) {
            if (record.headers() == null) {
                return null;
            }

            for (org.apache.kafka.connect.header.Header header : record.headers()) {
                if (header.key().equals(headerName)) {
                    Object value = header.value();
                    if (value != null) {
                        if (value instanceof byte[]) {
                            return new String((byte[]) value, StandardCharsets.UTF_8);
                        }
                        return value.toString();
                    }
                }
            }
            return null;
        }
    }
}
