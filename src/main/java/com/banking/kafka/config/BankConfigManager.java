package com.banking.kafka.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manager for bank-specific configurations.
 *
 * Loads and caches configuration for multiple banks from a JSON file.
 * Each bank can have different:
 * - PAN transformation strategy (REMOVE, DECRYPT, REKEY, NONE)
 * - PGP encryption settings
 * - S3 storage configuration
 *
 * Example configuration:
 * <pre>
 * {
 *   "banks": {
 *     "BNK001": {
 *       "pan_strategy": "REMOVE",
 *       "pgp_encryption": { "enabled": true, "public_key_path": "..." }
 *     }
 *   }
 * }
 * </pre>
 */
public class BankConfigManager {

    private static final Logger log = LoggerFactory.getLogger(BankConfigManager.class);

    private final ObjectMapper mapper;
    private final Map<String, BankConfig> configCache;
    private BankConfig defaultConfig;

    public BankConfigManager() {
        this.mapper = new ObjectMapper();
        this.configCache = new ConcurrentHashMap<>();
    }

    /**
     * Load bank configurations from a JSON file.
     *
     * @param configFilePath Path to the configuration file
     * @throws IOException if file cannot be read
     */
    public void loadConfig(String configFilePath) throws IOException {
        log.info("Loading bank configurations from: {}", configFilePath);

        File configFile = new File(configFilePath);
        if (!configFile.exists()) {
            log.warn("Configuration file not found: {}, using default configuration", configFilePath);
            loadDefaultConfig();
            return;
        }

        JsonNode root = mapper.readTree(configFile);
        JsonNode banksNode = root.get("banks");

        if (banksNode != null && banksNode.isObject()) {
            banksNode.fields().forEachRemaining(entry -> {
                String bankCode = entry.getKey();
                JsonNode bankNode = entry.getValue();
                BankConfig config = parseBankConfig(bankCode, bankNode);
                configCache.put(bankCode, config);
                log.info("Loaded configuration for bank: {} ({})", bankCode, config.getName());
            });
        }

        // Load default configuration
        JsonNode defaultNode = root.get("default");
        if (defaultNode != null) {
            this.defaultConfig = parseBankConfig("DEFAULT", defaultNode);
            log.info("Loaded default configuration");
        } else {
            loadDefaultConfig();
        }

        log.info("Loaded configurations for {} banks", configCache.size());
    }

    /**
     * Get configuration for a specific bank.
     *
     * @param bankCode Bank institution code (e.g., "BNK001")
     * @return Bank configuration, or default if not found
     */
    public BankConfig getConfig(String bankCode) {
        BankConfig config = configCache.get(bankCode);
        if (config == null) {
            log.debug("No configuration found for bank: {}, using default", bankCode);
            return defaultConfig != null ? defaultConfig : getHardcodedDefault();
        }
        return config;
    }

    /**
     * Parse bank configuration from JSON node.
     */
    private BankConfig parseBankConfig(String bankCode, JsonNode node) {
        BankConfig config = new BankConfig();
        config.setBankCode(bankCode);

        if (node.has("name")) {
            config.setName(node.get("name").asText());
        }

        // PAN strategy
        if (node.has("pan_strategy")) {
            String strategy = node.get("pan_strategy").asText();
            config.setPanStrategy(strategy);
        }

        // PAN config
        if (node.has("pan_config")) {
            JsonNode panConfig = node.get("pan_config");
            Map<String, String> panConfigMap = new HashMap<>();

            if (panConfig.has("source_field")) {
                panConfigMap.put("source_field", panConfig.get("source_field").asText());
            }
            if (panConfig.has("target_field")) {
                panConfigMap.put("target_field", panConfig.get("target_field").asText());
            }
            if (panConfig.has("private_key_path")) {
                panConfigMap.put("private_key_path", panConfig.get("private_key_path").asText());
            }
            if (panConfig.has("reason")) {
                panConfigMap.put("reason", panConfig.get("reason").asText());
            }

            config.setPanConfig(panConfigMap);
        }

        // PGP encryption
        if (node.has("pgp_encryption")) {
            JsonNode pgpNode = node.get("pgp_encryption");
            PGPConfig pgpConfig = new PGPConfig();

            if (pgpNode.has("enabled")) {
                pgpConfig.setEnabled(pgpNode.get("enabled").asBoolean());
            }
            if (pgpNode.has("public_key_path")) {
                pgpConfig.setPublicKeyPath(pgpNode.get("public_key_path").asText());
            }
            if (pgpNode.has("armor")) {
                pgpConfig.setArmor(pgpNode.get("armor").asBoolean());
            }
            if (pgpNode.has("reason")) {
                pgpConfig.setReason(pgpNode.get("reason").asText());
            }

            config.setPgpConfig(pgpConfig);
        }

        // S3 config
        if (node.has("s3_config")) {
            JsonNode s3Node = node.get("s3_config");
            Map<String, String> s3Config = new HashMap<>();

            if (s3Node.has("bucket")) {
                s3Config.put("bucket", s3Node.get("bucket").asText());
            }
            if (s3Node.has("path_prefix")) {
                s3Config.put("path_prefix", s3Node.get("path_prefix").asText());
            }

            config.setS3Config(s3Config);
        }

        return config;
    }

    /**
     * Load default hardcoded configuration.
     */
    private void loadDefaultConfig() {
        this.defaultConfig = getHardcodedDefault();
        log.info("Loaded hardcoded default configuration");
    }

    /**
     * Get hardcoded default configuration.
     */
    private BankConfig getHardcodedDefault() {
        BankConfig config = new BankConfig();
        config.setBankCode("DEFAULT");
        config.setName("Default Configuration");
        config.setPanStrategy("REMOVE");

        Map<String, String> panConfig = new HashMap<>();
        panConfig.put("source_field", "encryptedPrimaryAccountNumber");
        panConfig.put("reason", "Default: remove PAN for security");
        config.setPanConfig(panConfig);

        PGPConfig pgpConfig = new PGPConfig();
        pgpConfig.setEnabled(false);
        config.setPgpConfig(pgpConfig);

        return config;
    }

    /**
     * Bank configuration POJO.
     */
    public static class BankConfig {
        private String bankCode;
        private String name;
        private String panStrategy;
        private Map<String, String> panConfig;
        private PGPConfig pgpConfig;
        private Map<String, String> s3Config;

        // Getters and setters
        public String getBankCode() { return bankCode; }
        public void setBankCode(String bankCode) { this.bankCode = bankCode; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getPanStrategy() { return panStrategy; }
        public void setPanStrategy(String panStrategy) { this.panStrategy = panStrategy; }

        public Map<String, String> getPanConfig() { return panConfig; }
        public void setPanConfig(Map<String, String> panConfig) { this.panConfig = panConfig; }

        public PGPConfig getPgpConfig() { return pgpConfig; }
        public void setPgpConfig(PGPConfig pgpConfig) { this.pgpConfig = pgpConfig; }

        public Map<String, String> getS3Config() { return s3Config; }
        public void setS3Config(Map<String, String> s3Config) { this.s3Config = s3Config; }
    }

    /**
     * PGP configuration POJO.
     */
    public static class PGPConfig {
        private boolean enabled;
        private String publicKeyPath;
        private boolean armor;
        private String reason;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getPublicKeyPath() { return publicKeyPath; }
        public void setPublicKeyPath(String publicKeyPath) { this.publicKeyPath = publicKeyPath; }

        public boolean isArmor() { return armor; }
        public void setArmor(boolean armor) { this.armor = armor; }

        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }
}
