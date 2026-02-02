package com.banking.kafka.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * File-based implementation of KeyStorageProvider.
 *
 * Loads RSA keys from PEM files on the filesystem.
 * Keys are cached in memory after first load for performance.
 *
 * Expected key format: PEM (PKCS#8 for private keys, X.509 for public keys)
 */
public class FileKeyStorageProvider implements KeyStorageProvider {

    private static final Logger log = LoggerFactory.getLogger(FileKeyStorageProvider.class);

    private final String privateKeyPath;
    private final Map<String, String> partnerKeysMapping;

    // Cache for loaded keys
    private final Map<String, RSAPrivateKey> privateKeyCache = new ConcurrentHashMap<>();
    private final Map<String, RSAPublicKey> publicKeyCache = new ConcurrentHashMap<>();

    /**
     * Constructor for FileKeyStorageProvider.
     *
     * @param privateKeyPath Path to our private key file
     * @param partnerKeysMapping Map of institution ID to public key file path
     */
    public FileKeyStorageProvider(String privateKeyPath, Map<String, String> partnerKeysMapping) {
        this.privateKeyPath = privateKeyPath;
        this.partnerKeysMapping = partnerKeysMapping != null ? partnerKeysMapping : Map.of();
    }

    @Override
    public RSAPrivateKey getPrivateKey(String keyId) throws KeyStorageException {
        // For our own private key, keyId can be null (use default)
        String cacheKey = keyId != null ? keyId : "default";

        // Check cache first
        if (privateKeyCache.containsKey(cacheKey)) {
            log.debug("Private key loaded from cache: {}", cacheKey);
            return privateKeyCache.get(cacheKey);
        }

        // Load from file
        try {
            RSAPrivateKey privateKey = loadPrivateKeyFromFile(privateKeyPath);
            privateKeyCache.put(cacheKey, privateKey);
            log.info("Private key loaded from file: {}", privateKeyPath);
            return privateKey;
        } catch (Exception e) {
            throw new KeyStorageException("Failed to load private key from " + privateKeyPath, e);
        }
    }

    @Override
    public RSAPublicKey getPublicKey(String keyId) throws KeyStorageException {
        if (keyId == null || keyId.trim().isEmpty()) {
            throw new KeyStorageException("keyId is required for public key lookup");
        }

        // Check cache first
        if (publicKeyCache.containsKey(keyId)) {
            log.debug("Public key loaded from cache: {}", keyId);
            return publicKeyCache.get(keyId);
        }

        // Get path from mapping
        String publicKeyPath = partnerKeysMapping.get(keyId);
        if (publicKeyPath == null) {
            throw new KeyStorageException("No public key path found for institution: " + keyId);
        }

        // Load from file
        try {
            RSAPublicKey publicKey = loadPublicKeyFromFile(publicKeyPath);
            publicKeyCache.put(keyId, publicKey);
            log.info("Public key loaded from file: {} for institution: {}", publicKeyPath, keyId);
            return publicKey;
        } catch (Exception e) {
            throw new KeyStorageException("Failed to load public key from " + publicKeyPath, e);
        }
    }

    /**
     * Load RSA private key from PEM file (PKCS#8 format).
     */
    private RSAPrivateKey loadPrivateKeyFromFile(String filePath) throws Exception {
        // Read PEM file
        String pem = new String(Files.readAllBytes(Paths.get(filePath)));

        // Remove PEM headers and footers
        pem = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                 .replace("-----END PRIVATE KEY-----", "")
                 .replaceAll("\\s", "");

        // Decode Base64
        byte[] keyBytes = Base64.getDecoder().decode(pem);

        // Generate RSA private key
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return (RSAPrivateKey) keyFactory.generatePrivate(keySpec);
    }

    /**
     * Load RSA public key from PEM file (X.509 format).
     */
    private RSAPublicKey loadPublicKeyFromFile(String filePath) throws Exception {
        // Read PEM file
        String pem = new String(Files.readAllBytes(Paths.get(filePath)));

        // Remove PEM headers and footers
        pem = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                 .replace("-----END PUBLIC KEY-----", "")
                 .replaceAll("\\s", "");

        // Decode Base64
        byte[] keyBytes = Base64.getDecoder().decode(pem);

        // Generate RSA public key
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return (RSAPublicKey) keyFactory.generatePublic(keySpec);
    }
}
