package com.banking.kafka.crypto;

import com.banking.kafka.config.BankConfigManager;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PGP Encryptor that applies bank-specific encryption policies.
 *
 * Each bank can have its own:
 * - PGP public key
 * - ASCII armor preference
 * - Encryption enabled/disabled
 *
 * Usage:
 * <pre>
 * BankPGPEncryptor encryptor = new BankPGPEncryptor(configManager);
 * byte[] encrypted = encryptor.encryptForBank("BNK001", data);
 * </pre>
 */
public class BankPGPEncryptor {

    private static final Logger log = LoggerFactory.getLogger(BankPGPEncryptor.class);

    private final BankConfigManager configManager;
    private final PGPEncryptionHandler pgpHandler;
    private final Map<String, PGPPublicKey> keyCache;

    public BankPGPEncryptor(BankConfigManager configManager) {
        this.configManager = configManager;
        this.pgpHandler = new PGPEncryptionHandler();
        this.keyCache = new ConcurrentHashMap<>();
    }

    /**
     * Encrypt data for a specific bank using their PGP configuration.
     *
     * @param bankCode Bank institution code (e.g., "BNK001")
     * @param data Data to encrypt
     * @return Encrypted data, or original data if PGP not enabled for this bank
     * @throws PGPEncryptionHandler.PGPException if encryption fails
     */
    public byte[] encryptForBank(String bankCode, byte[] data) throws PGPEncryptionHandler.PGPException {
        if (data == null || data.length == 0) {
            return data;
        }

        BankConfigManager.BankConfig config = configManager.getConfig(bankCode);
        BankConfigManager.PGPConfig pgpConfig = config.getPgpConfig();

        if (pgpConfig == null || !pgpConfig.isEnabled()) {
            log.debug("PGP encryption not enabled for bank: {}", bankCode);
            return data;
        }

        try {
            // Load or get cached public key
            PGPPublicKey publicKey = getPublicKey(bankCode, pgpConfig.getPublicKeyPath());

            // Encrypt data
            boolean armor = pgpConfig.isArmor();
            byte[] encrypted = pgpHandler.encrypt(data, publicKey, armor);

            log.info("Encrypted {} bytes for bank {} using PGP (armor={})",
                    data.length, bankCode, armor);

            return encrypted;

        } catch (Exception e) {
            throw new PGPEncryptionHandler.PGPException(
                    "Failed to encrypt data for bank " + bankCode, e);
        }
    }

    /**
     * Check if PGP encryption is enabled for a bank.
     *
     * @param bankCode Bank institution code
     * @return true if PGP encryption is enabled
     */
    public boolean isPGPEnabledForBank(String bankCode) {
        BankConfigManager.BankConfig config = configManager.getConfig(bankCode);
        BankConfigManager.PGPConfig pgpConfig = config.getPgpConfig();
        return pgpConfig != null && pgpConfig.isEnabled();
    }

    /**
     * Get bank-specific PGP configuration.
     *
     * @param bankCode Bank institution code
     * @return PGP configuration for the bank
     */
    public BankConfigManager.PGPConfig getPGPConfig(String bankCode) {
        return configManager.getConfig(bankCode).getPgpConfig();
    }

    /**
     * Load or get cached PGP public key for a bank.
     */
    private PGPPublicKey getPublicKey(String bankCode, String keyPath)
            throws PGPEncryptionHandler.PGPException {

        // Check cache first
        PGPPublicKey cachedKey = keyCache.get(bankCode);
        if (cachedKey != null) {
            return cachedKey;
        }

        // Load key from file
        log.info("Loading PGP public key for bank {} from: {}", bankCode, keyPath);
        PGPPublicKey key = pgpHandler.loadPublicKey(keyPath);

        // Cache for future use
        keyCache.put(bankCode, key);

        return key;
    }

    /**
     * Clear the key cache (useful for testing or key rotation).
     */
    public void clearCache() {
        keyCache.clear();
        log.info("Cleared PGP key cache");
    }

    /**
     * Create a streaming PGP encryption wrapper for a specific bank.
     * Data written to the returned stream is encrypted on the fly.
     *
     * @param bankCode Bank institution code (e.g., "BNK001")
     * @param target The underlying output stream to write encrypted data to
     * @return A PGPOutputStreamWrapper, or the original stream if PGP is disabled for this bank
     * @throws PGPEncryptionHandler.PGPException if encryption setup fails
     */
    public java.io.OutputStream createStreamingEncryptorForBank(String bankCode, java.io.OutputStream target)
            throws PGPEncryptionHandler.PGPException {

        BankConfigManager.BankConfig config = configManager.getConfig(bankCode);
        BankConfigManager.PGPConfig pgpConfig = config.getPgpConfig();

        if (pgpConfig == null || !pgpConfig.isEnabled()) {
            log.debug("PGP not enabled for bank {}, returning passthrough stream", bankCode);
            return target;
        }

        PGPPublicKey publicKey = getPublicKey(bankCode, pgpConfig.getPublicKeyPath());
        boolean armor = pgpConfig.isArmor();

        log.info("Creating streaming PGP encryptor for bank {} (armor={})", bankCode, armor);
        return pgpHandler.createStreamingEncryptor(target, publicKey, armor);
    }

    /**
     * Encrypt a file for a specific bank.
     *
     * @param bankCode Bank institution code
     * @param inputFile Path to input file
     * @param outputFile Path to output file
     * @throws PGPEncryptionHandler.PGPException if encryption fails
     */
    public void encryptFileForBank(String bankCode, String inputFile, String outputFile)
            throws PGPEncryptionHandler.PGPException, IOException {

        BankConfigManager.BankConfig config = configManager.getConfig(bankCode);
        BankConfigManager.PGPConfig pgpConfig = config.getPgpConfig();

        if (pgpConfig == null || !pgpConfig.isEnabled()) {
            log.warn("PGP encryption not enabled for bank: {}, file will not be encrypted", bankCode);
            return;
        }

        // Load public key
        PGPPublicKey publicKey = getPublicKey(bankCode, pgpConfig.getPublicKeyPath());
        boolean armor = pgpConfig.isArmor();

        // Read input file
        byte[] data = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(inputFile));

        // Encrypt
        byte[] encrypted = pgpHandler.encrypt(data, publicKey, armor);

        // Write encrypted file
        java.nio.file.Files.write(java.nio.file.Paths.get(outputFile), encrypted);

        log.info("Encrypted file {} to {} for bank {} (size: {} -> {} bytes, armor={})",
                inputFile, outputFile, bankCode, data.length, encrypted.length, armor);
    }
}
