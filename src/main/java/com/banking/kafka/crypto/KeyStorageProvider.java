package com.banking.kafka.crypto;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

/**
 * Interface for key storage providers.
 *
 * Implementations can load keys from various sources:
 * - File system (FileKeyStorageProvider)
 * - IBM Key Protect (IBMKeyProtectProvider)
 * - AWS KMS, Azure Key Vault, etc.
 */
public interface KeyStorageProvider {

    /**
     * Get the private RSA key for decryption.
     *
     * @param keyId Optional key identifier (can be null for default key)
     * @return RSA private key
     * @throws KeyStorageException if key cannot be loaded
     */
    RSAPrivateKey getPrivateKey(String keyId) throws KeyStorageException;

    /**
     * Get the public RSA key for encryption.
     *
     * @param keyId Key identifier (required for partner keys)
     * @return RSA public key
     * @throws KeyStorageException if key cannot be loaded
     */
    RSAPublicKey getPublicKey(String keyId) throws KeyStorageException;

    /**
     * Exception thrown when key loading fails.
     */
    class KeyStorageException extends Exception {
        public KeyStorageException(String message) {
            super(message);
        }

        public KeyStorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
