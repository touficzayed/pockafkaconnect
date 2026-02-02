package com.banking.kafka.crypto;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSADecrypter;
import com.nimbusds.jose.crypto.RSAEncrypter;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;

/**
 * Handler for JWE (JSON Web Encryption) operations on PAN (Primary Account Number).
 *
 * Uses RSA-OAEP-256 algorithm for key encryption and A256GCM for content encryption.
 * This provides strong encryption suitable for PCI-DSS compliance.
 */
public class JWEHandler {

    // JWE algorithm for key encryption
    private static final JWEAlgorithm KEY_ALGORITHM = JWEAlgorithm.RSA_OAEP_256;

    // JWE algorithm for content encryption
    private static final EncryptionMethod CONTENT_ALGORITHM = EncryptionMethod.A256GCM;

    /**
     * Decrypt a JWE-encrypted PAN using RSA private key.
     *
     * @param jweString JWE compact serialization string
     * @param privateKey RSA private key for decryption
     * @return Decrypted plaintext PAN
     * @throws JWEException if decryption fails
     */
    public String decrypt(String jweString, RSAPrivateKey privateKey) throws JWEException {
        if (jweString == null || jweString.trim().isEmpty()) {
            throw new JWEException("JWE string cannot be null or empty");
        }

        if (privateKey == null) {
            throw new JWEException("Private key cannot be null");
        }

        try {
            // Parse JWE
            JWEObject jweObject = JWEObject.parse(jweString);

            // Create RSA decrypter
            JWEDecrypter decrypter = new RSADecrypter(privateKey);

            // Decrypt
            jweObject.decrypt(decrypter);

            // Get plaintext
            return jweObject.getPayload().toString();

        } catch (ParseException e) {
            throw new JWEException("Failed to parse JWE: " + e.getMessage(), e);
        } catch (JOSEException e) {
            throw new JWEException("Failed to decrypt JWE: " + e.getMessage(), e);
        }
    }

    /**
     * Encrypt a plaintext PAN using RSA public key.
     *
     * @param plaintext Plaintext PAN
     * @param publicKey RSA public key for encryption
     * @return JWE compact serialization string
     * @throws JWEException if encryption fails
     */
    public String encrypt(String plaintext, RSAPublicKey publicKey) throws JWEException {
        if (plaintext == null || plaintext.trim().isEmpty()) {
            throw new JWEException("Plaintext cannot be null or empty");
        }

        if (publicKey == null) {
            throw new JWEException("Public key cannot be null");
        }

        try {
            // Create JWE header
            JWEHeader header = new JWEHeader(KEY_ALGORITHM, CONTENT_ALGORITHM);

            // Create payload
            Payload payload = new Payload(plaintext);

            // Create JWE object
            JWEObject jweObject = new JWEObject(header, payload);

            // Create RSA encrypter
            JWEEncrypter encrypter = new RSAEncrypter(publicKey);

            // Encrypt
            jweObject.encrypt(encrypter);

            // Serialize to compact form
            return jweObject.serialize();

        } catch (JOSEException e) {
            throw new JWEException("Failed to encrypt: " + e.getMessage(), e);
        }
    }

    /**
     * Rekey: Decrypt with one key and re-encrypt with another.
     *
     * @param jweString JWE compact serialization string
     * @param decryptionKey RSA private key for decryption
     * @param encryptionKey RSA public key for re-encryption
     * @return New JWE compact serialization string
     * @throws JWEException if rekey operation fails
     */
    public String rekey(String jweString, RSAPrivateKey decryptionKey, RSAPublicKey encryptionKey) throws JWEException {
        // Decrypt with our private key
        String plaintext = decrypt(jweString, decryptionKey);

        // Re-encrypt with partner's public key
        return encrypt(plaintext, encryptionKey);
    }

    /**
     * Exception thrown when JWE operations fail.
     */
    public static class JWEException extends Exception {
        public JWEException(String message) {
            super(message);
        }

        public JWEException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
