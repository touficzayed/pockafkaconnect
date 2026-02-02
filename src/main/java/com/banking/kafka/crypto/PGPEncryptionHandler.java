package com.banking.kafka.crypto;

import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.*;
import org.bouncycastle.openpgp.jcajce.JcaPGPObjectFactory;
import org.bouncycastle.openpgp.operator.jcajce.*;
import org.bouncycastle.util.io.Streams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Iterator;

/**
 * Handler for PGP encryption and decryption operations.
 *
 * This class provides methods to:
 * - Encrypt data using PGP public keys
 * - Decrypt data using PGP private keys
 * - Load PGP keys from files
 *
 * Used in the Banking POC to add an additional layer of encryption
 * to files stored in S3/MinIO.
 *
 * Usage:
 * <pre>
 * PGPEncryptionHandler handler = new PGPEncryptionHandler();
 * byte[] encrypted = handler.encrypt(data, publicKey);
 * byte[] decrypted = handler.decrypt(encrypted, privateKey, passphrase);
 * </pre>
 */
public class PGPEncryptionHandler {

    private static final Logger log = LoggerFactory.getLogger(PGPEncryptionHandler.class);

    static {
        // Register BouncyCastle as security provider
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    /**
     * Exception thrown when PGP operations fail.
     */
    public static class PGPException extends Exception {
        public PGPException(String message) {
            super(message);
        }

        public PGPException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Encrypt data using a PGP public key.
     *
     * @param data Data to encrypt
     * @param publicKey PGP public key
     * @param armor Whether to ASCII armor the output
     * @return Encrypted data
     * @throws PGPException if encryption fails
     */
    public byte[] encrypt(byte[] data, PGPPublicKey publicKey, boolean armor) throws PGPException {
        try {
            ByteArrayOutputStream encryptedOut = new ByteArrayOutputStream();
            OutputStream out = armor ? new ArmoredOutputStream(encryptedOut) : encryptedOut;

            // Create encrypted data generator
            PGPEncryptedDataGenerator encGen = new PGPEncryptedDataGenerator(
                    new JcePGPDataEncryptorBuilder(PGPEncryptedData.AES_256)
                            .setWithIntegrityPacket(true)
                            .setSecureRandom(new SecureRandom())
                            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            );

            encGen.addMethod(new JcePublicKeyKeyEncryptionMethodGenerator(publicKey)
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME));

            OutputStream encryptedStream = encGen.open(out, new byte[4096]);

            // Create compressed data generator
            PGPCompressedDataGenerator compGen = new PGPCompressedDataGenerator(
                    PGPCompressedData.ZIP
            );
            OutputStream compressedStream = compGen.open(encryptedStream);

            // Create literal data generator
            PGPLiteralDataGenerator litGen = new PGPLiteralDataGenerator();
            OutputStream litStream = litGen.open(
                    compressedStream,
                    PGPLiteralData.BINARY,
                    "encrypted-data",
                    data.length,
                    new java.util.Date()
            );

            // Write data
            litStream.write(data);

            // Close all streams
            litStream.close();
            compGen.close();
            encryptedStream.close();
            out.close();

            log.debug("Successfully encrypted {} bytes using PGP", data.length);
            return encryptedOut.toByteArray();

        } catch (Exception e) {
            throw new PGPException("Failed to encrypt data with PGP", e);
        }
    }

    /**
     * Decrypt data using a PGP private key.
     *
     * @param encryptedData Encrypted data
     * @param privateKey PGP private key
     * @param passphrase Passphrase to unlock the private key
     * @return Decrypted data
     * @throws PGPException if decryption fails
     */
    public byte[] decrypt(byte[] encryptedData, PGPPrivateKey privateKey) throws PGPException {
        try {
            InputStream in = new ByteArrayInputStream(encryptedData);
            in = PGPUtil.getDecoderStream(in);

            JcaPGPObjectFactory pgpFactory = new JcaPGPObjectFactory(in);
            Object obj = pgpFactory.nextObject();

            // Handle encrypted data list
            PGPEncryptedDataList encDataList;
            if (obj instanceof PGPEncryptedDataList) {
                encDataList = (PGPEncryptedDataList) obj;
            } else {
                encDataList = (PGPEncryptedDataList) pgpFactory.nextObject();
            }

            // Find the encrypted data that matches our private key
            PGPPublicKeyEncryptedData encData = null;
            Iterator<?> it = encDataList.getEncryptedDataObjects();
            while (it.hasNext()) {
                Object next = it.next();
                if (next instanceof PGPPublicKeyEncryptedData) {
                    PGPPublicKeyEncryptedData pked = (PGPPublicKeyEncryptedData) next;
                    if (pked.getKeyID() == privateKey.getKeyID()) {
                        encData = pked;
                        break;
                    }
                }
            }

            if (encData == null) {
                // Try the first one if no match found
                it = encDataList.getEncryptedDataObjects();
                if (it.hasNext()) {
                    encData = (PGPPublicKeyEncryptedData) it.next();
                } else {
                    throw new PGPException("No encrypted data found");
                }
            }

            // Decrypt the data
            InputStream decryptedStream = encData.getDataStream(
                    new JcePublicKeyDataDecryptorFactoryBuilder()
                            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                            .build(privateKey)
            );

            JcaPGPObjectFactory plainFactory = new JcaPGPObjectFactory(decryptedStream);
            Object message = plainFactory.nextObject();

            // Handle compressed data
            if (message instanceof PGPCompressedData) {
                PGPCompressedData compressedData = (PGPCompressedData) message;
                plainFactory = new JcaPGPObjectFactory(compressedData.getDataStream());
                message = plainFactory.nextObject();
            }

            // Extract literal data
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (message instanceof PGPLiteralData) {
                PGPLiteralData literalData = (PGPLiteralData) message;
                Streams.pipeAll(literalData.getInputStream(), out);
            } else {
                throw new PGPException("Unexpected message type: " + message.getClass().getName());
            }

            // Verify integrity
            if (encData.isIntegrityProtected() && !encData.verify()) {
                throw new PGPException("Data integrity check failed");
            }

            byte[] decrypted = out.toByteArray();
            log.debug("Successfully decrypted {} bytes using PGP", decrypted.length);
            return decrypted;

        } catch (Exception e) {
            throw new PGPException("Failed to decrypt data with PGP", e);
        }
    }

    /**
     * Load a PGP public key from a file.
     *
     * @param keyFile Path to the public key file
     * @return PGP public key
     * @throws PGPException if key loading fails
     */
    public PGPPublicKey loadPublicKey(String keyFile) throws PGPException {
        try (InputStream keyIn = new FileInputStream(keyFile)) {
            return loadPublicKey(keyIn);
        } catch (IOException e) {
            throw new PGPException("Failed to load public key from file: " + keyFile, e);
        }
    }

    /**
     * Load a PGP public key from an input stream.
     *
     * @param keyIn Input stream containing the public key
     * @return PGP public key
     * @throws PGPException if key loading fails
     */
    public PGPPublicKey loadPublicKey(InputStream keyIn) throws PGPException {
        try {
            keyIn = PGPUtil.getDecoderStream(keyIn);
            PGPPublicKeyRingCollection keyRingCollection = new PGPPublicKeyRingCollection(
                    keyIn, new JcaKeyFingerprintCalculator()
            );

            Iterator<PGPPublicKeyRing> keyRings = keyRingCollection.getKeyRings();
            while (keyRings.hasNext()) {
                PGPPublicKeyRing keyRing = keyRings.next();
                Iterator<PGPPublicKey> keys = keyRing.getPublicKeys();
                while (keys.hasNext()) {
                    PGPPublicKey key = keys.next();
                    if (key.isEncryptionKey()) {
                        log.debug("Loaded PGP public key with ID: {}", Long.toHexString(key.getKeyID()));
                        return key;
                    }
                }
            }

            throw new PGPException("No encryption key found in public key ring");
        } catch (Exception e) {
            throw new PGPException("Failed to load public key", e);
        }
    }

    /**
     * Load a PGP private key from a file.
     *
     * @param keyFile Path to the private key file
     * @param passphrase Passphrase to unlock the key
     * @return PGP private key
     * @throws PGPException if key loading fails
     */
    public PGPPrivateKey loadPrivateKey(String keyFile, String passphrase) throws PGPException {
        try (InputStream keyIn = new FileInputStream(keyFile)) {
            return loadPrivateKey(keyIn, passphrase);
        } catch (IOException e) {
            throw new PGPException("Failed to load private key from file: " + keyFile, e);
        }
    }

    /**
     * Load a PGP private key from an input stream.
     *
     * @param keyIn Input stream containing the private key
     * @param passphrase Passphrase to unlock the key
     * @return PGP private key
     * @throws PGPException if key loading fails
     */
    public PGPPrivateKey loadPrivateKey(InputStream keyIn, String passphrase) throws PGPException {
        try {
            keyIn = PGPUtil.getDecoderStream(keyIn);
            PGPSecretKeyRingCollection keyRingCollection = new PGPSecretKeyRingCollection(
                    keyIn, new JcaKeyFingerprintCalculator()
            );

            Iterator<PGPSecretKeyRing> keyRings = keyRingCollection.getKeyRings();
            while (keyRings.hasNext()) {
                PGPSecretKeyRing keyRing = keyRings.next();
                Iterator<PGPSecretKey> keys = keyRing.getSecretKeys();
                while (keys.hasNext()) {
                    PGPSecretKey secretKey = keys.next();

                    // Try to extract private key from any suitable secret key
                    try {
                        PGPPrivateKey privateKey = secretKey.extractPrivateKey(
                                new JcePBESecretKeyDecryptorBuilder()
                                        .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                                        .build(passphrase.toCharArray())
                        );

                        if (privateKey != null) {
                            log.debug("Loaded PGP private key with ID: {}", Long.toHexString(privateKey.getKeyID()));
                            return privateKey;
                        }
                    } catch (Exception e) {
                        // Try next key
                        log.debug("Failed to extract private key from key {}: {}",
                                Long.toHexString(secretKey.getKeyID()), e.getMessage());
                    }
                }
            }

            throw new PGPException("No suitable private key found in secret key ring");
        } catch (Exception e) {
            throw new PGPException("Failed to load private key", e);
        }
    }

    /**
     * Create a streaming PGP encryption wrapper around an existing OutputStream.
     * Data written to the returned wrapper is encrypted on the fly without buffering
     * the entire content in memory.
     *
     * @param target The underlying output stream to write encrypted data to
     * @param publicKey PGP public key to encrypt with
     * @param armor Whether to ASCII armor the output
     * @return A PGPOutputStreamWrapper that encrypts data as it is written
     * @throws PGPException if stream setup fails
     */
    public PGPOutputStreamWrapper createStreamingEncryptor(java.io.OutputStream target,
                                                            PGPPublicKey publicKey,
                                                            boolean armor) throws PGPException {
        try {
            return new PGPOutputStreamWrapper(target, publicKey, armor);
        } catch (java.io.IOException e) {
            throw new PGPException("Failed to create streaming PGP encryptor", e);
        }
    }

    /**
     * Encrypt a string using a PGP public key.
     *
     * @param plaintext Plain text to encrypt
     * @param publicKey PGP public key
     * @param armor Whether to ASCII armor the output
     * @return Encrypted string (Base64 encoded if not armored)
     * @throws PGPException if encryption fails
     */
    public String encryptString(String plaintext, PGPPublicKey publicKey, boolean armor) throws PGPException {
        byte[] encrypted = encrypt(plaintext.getBytes(StandardCharsets.UTF_8), publicKey, armor);
        return armor ? new String(encrypted, StandardCharsets.UTF_8)
                     : java.util.Base64.getEncoder().encodeToString(encrypted);
    }

    /**
     * Decrypt a string using a PGP private key.
     *
     * @param encryptedString Encrypted string
     * @param privateKey PGP private key
     * @param armored Whether the input is ASCII armored
     * @return Decrypted plain text
     * @throws PGPException if decryption fails
     */
    public String decryptString(String encryptedString, PGPPrivateKey privateKey, boolean armored) throws PGPException {
        byte[] encrypted = armored ? encryptedString.getBytes(StandardCharsets.UTF_8)
                                    : java.util.Base64.getDecoder().decode(encryptedString);
        byte[] decrypted = decrypt(encrypted, privateKey);
        return new String(decrypted, StandardCharsets.UTF_8);
    }
}
