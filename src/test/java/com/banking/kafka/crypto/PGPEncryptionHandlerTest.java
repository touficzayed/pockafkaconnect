package com.banking.kafka.crypto;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.*;
import org.bouncycastle.openpgp.operator.PGPDigestCalculator;
import org.bouncycastle.openpgp.operator.jcajce.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PGPEncryptionHandler
 */
class PGPEncryptionHandlerTest {

    private PGPEncryptionHandler handler;
    private PGPPublicKey publicKey;
    private PGPPrivateKey privateKey;
    private static final String TEST_PASSPHRASE = "test-passphrase";

    @TempDir
    Path tempDir;

    @BeforeAll
    static void setUpBouncyCastle() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        handler = new PGPEncryptionHandler();

        // Generate test PGP key pair
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME);
        keyGen.initialize(2048);
        KeyPair keyPair = keyGen.generateKeyPair();

        // Create PGP key ring generator
        PGPDigestCalculator sha1Calc = new JcaPGPDigestCalculatorProviderBuilder()
                .build()
                .get(org.bouncycastle.bcpg.HashAlgorithmTags.SHA1);

        // Convert KeyPair to PGPKeyPair
        PGPKeyPair pgpKeyPair = new JcaPGPKeyPair(PGPPublicKey.RSA_GENERAL, keyPair, new Date());

        PGPKeyRingGenerator keyRingGen = new PGPKeyRingGenerator(
                PGPSignature.POSITIVE_CERTIFICATION,
                pgpKeyPair,
                "test@example.com",
                sha1Calc,
                null,
                null,
                new JcaPGPContentSignerBuilder(pgpKeyPair.getPublicKey().getAlgorithm(),
                        org.bouncycastle.bcpg.HashAlgorithmTags.SHA256)
                        .setProvider(BouncyCastleProvider.PROVIDER_NAME),
                new JcePBESecretKeyEncryptorBuilder(PGPEncryptedData.AES_256, sha1Calc)
                        .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                        .build(TEST_PASSPHRASE.toCharArray())
        );

        // Extract public and private keys
        PGPPublicKeyRing publicKeyRing = keyRingGen.generatePublicKeyRing();
        PGPSecretKeyRing secretKeyRing = keyRingGen.generateSecretKeyRing();

        // Get the first encryption key
        this.publicKey = publicKeyRing.getPublicKey();
        PGPSecretKey secretKey = secretKeyRing.getSecretKey();
        this.privateKey = secretKey.extractPrivateKey(
                new JcePBESecretKeyDecryptorBuilder()
                        .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                        .build(TEST_PASSPHRASE.toCharArray())
        );
    }

    @Test
    void testEncryptDecrypt() throws Exception {
        // Test data
        byte[] originalData = "Hello, PGP World!".getBytes(StandardCharsets.UTF_8);

        // Encrypt
        byte[] encrypted = handler.encrypt(originalData, publicKey, false);
        assertNotNull(encrypted);
        assertTrue(encrypted.length > originalData.length, "Encrypted data should be larger");

        // Decrypt
        byte[] decrypted = handler.decrypt(encrypted, privateKey);
        assertNotNull(decrypted);
        assertArrayEquals(originalData, decrypted, "Decrypted data should match original");
    }

    @Test
    void testEncryptDecryptWithArmor() throws Exception {
        // Test data
        byte[] originalData = "Hello, PGP World with Armor!".getBytes(StandardCharsets.UTF_8);

        // Encrypt with ASCII armor
        byte[] encrypted = handler.encrypt(originalData, publicKey, true);
        assertNotNull(encrypted);

        // Verify it's ASCII armored
        String encryptedString = new String(encrypted, StandardCharsets.UTF_8);
        assertTrue(encryptedString.contains("-----BEGIN PGP MESSAGE-----"),
                "Should contain PGP armor header");

        // Decrypt
        byte[] decrypted = handler.decrypt(encrypted, privateKey);
        assertNotNull(decrypted);
        assertArrayEquals(originalData, decrypted, "Decrypted data should match original");
    }

    @Test
    void testEncryptDecryptLargeData() throws Exception {
        // Create large test data (1MB)
        byte[] originalData = new byte[1024 * 1024];
        for (int i = 0; i < originalData.length; i++) {
            originalData[i] = (byte) (i % 256);
        }

        // Encrypt
        byte[] encrypted = handler.encrypt(originalData, publicKey, false);
        assertNotNull(encrypted);

        // Decrypt
        byte[] decrypted = handler.decrypt(encrypted, privateKey);
        assertNotNull(decrypted);
        assertArrayEquals(originalData, decrypted, "Large data should decrypt correctly");
    }

    @Test
    void testEncryptDecryptString() throws Exception {
        String originalText = "This is a test message for PGP encryption!";

        // Encrypt string
        String encrypted = handler.encryptString(originalText, publicKey, false);
        assertNotNull(encrypted);
        assertNotEquals(originalText, encrypted);

        // Decrypt string
        String decrypted = handler.decryptString(encrypted, privateKey, false);
        assertEquals(originalText, decrypted, "Decrypted string should match original");
    }

    @Test
    void testEncryptDecryptStringWithArmor() throws Exception {
        String originalText = "This is a test message with ASCII armor!";

        // Encrypt string with armor
        String encrypted = handler.encryptString(originalText, publicKey, true);
        assertNotNull(encrypted);
        assertTrue(encrypted.contains("-----BEGIN PGP MESSAGE-----"),
                "Should contain PGP armor header");

        // Decrypt string
        String decrypted = handler.decryptString(encrypted, privateKey, true);
        assertEquals(originalText, decrypted, "Decrypted string should match original");
    }

    @Test
    void testEncryptDecryptEmptyData() throws Exception {
        byte[] originalData = new byte[0];

        // Encrypt empty data
        byte[] encrypted = handler.encrypt(originalData, publicKey, false);
        assertNotNull(encrypted);

        // Decrypt
        byte[] decrypted = handler.decrypt(encrypted, privateKey);
        assertNotNull(decrypted);
        assertEquals(0, decrypted.length, "Decrypted empty data should be empty");
    }

    @Test
    void testLoadPublicKeyFromFile() throws Exception {
        // Write public key to temp file
        Path publicKeyFile = tempDir.resolve("test-public.asc");
        try (FileOutputStream fos = new FileOutputStream(publicKeyFile.toFile());
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            publicKey.encode(baos);
            fos.write(baos.toByteArray());
        }

        // Load public key from file
        PGPPublicKey loadedKey = handler.loadPublicKey(publicKeyFile.toString());
        assertNotNull(loadedKey);
        assertEquals(publicKey.getKeyID(), loadedKey.getKeyID(),
                "Loaded key should have same ID as original");
    }

    @Test
    void testLoadPrivateKeyFromFile() throws Exception {
        // Create secret key ring
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME);
        keyGen.initialize(2048);
        KeyPair keyPair = keyGen.generateKeyPair();

        PGPDigestCalculator sha1Calc = new JcaPGPDigestCalculatorProviderBuilder()
                .build()
                .get(org.bouncycastle.bcpg.HashAlgorithmTags.SHA1);

        // Convert KeyPair to PGPKeyPair
        PGPKeyPair pgpKeyPair = new JcaPGPKeyPair(PGPPublicKey.RSA_GENERAL, keyPair, new Date());

        PGPKeyRingGenerator keyRingGen = new PGPKeyRingGenerator(
                PGPSignature.POSITIVE_CERTIFICATION,
                pgpKeyPair,
                "test@example.com",
                sha1Calc,
                null,
                null,
                new JcaPGPContentSignerBuilder(pgpKeyPair.getPublicKey().getAlgorithm(),
                        org.bouncycastle.bcpg.HashAlgorithmTags.SHA256)
                        .setProvider(BouncyCastleProvider.PROVIDER_NAME),
                new JcePBESecretKeyEncryptorBuilder(PGPEncryptedData.AES_256, sha1Calc)
                        .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                        .build(TEST_PASSPHRASE.toCharArray())
        );

        PGPSecretKeyRing secretKeyRing = keyRingGen.generateSecretKeyRing();

        // Write secret key to temp file
        Path privateKeyFile = tempDir.resolve("test-private.asc");
        try (FileOutputStream fos = new FileOutputStream(privateKeyFile.toFile())) {
            secretKeyRing.encode(fos);
        }

        // Load private key from file
        PGPPrivateKey loadedKey = handler.loadPrivateKey(privateKeyFile.toString(), TEST_PASSPHRASE);
        assertNotNull(loadedKey);
    }

    @Test
    void testDecryptWithWrongKey() throws Exception {
        // Encrypt with original public key
        byte[] originalData = "Secret message".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = handler.encrypt(originalData, publicKey, false);

        // Generate a different key pair
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME);
        keyGen.initialize(2048);
        KeyPair wrongKeyPair = keyGen.generateKeyPair();

        PGPDigestCalculator sha1Calc = new JcaPGPDigestCalculatorProviderBuilder()
                .build()
                .get(org.bouncycastle.bcpg.HashAlgorithmTags.SHA1);

        // Convert KeyPair to PGPKeyPair
        PGPKeyPair wrongPgpKeyPair = new JcaPGPKeyPair(PGPPublicKey.RSA_GENERAL, wrongKeyPair, new Date());

        PGPKeyRingGenerator wrongKeyRingGen = new PGPKeyRingGenerator(
                PGPSignature.POSITIVE_CERTIFICATION,
                wrongPgpKeyPair,
                "wrong@example.com",
                sha1Calc,
                null,
                null,
                new JcaPGPContentSignerBuilder(wrongPgpKeyPair.getPublicKey().getAlgorithm(),
                        org.bouncycastle.bcpg.HashAlgorithmTags.SHA256)
                        .setProvider(BouncyCastleProvider.PROVIDER_NAME),
                new JcePBESecretKeyEncryptorBuilder(PGPEncryptedData.AES_256, sha1Calc)
                        .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                        .build("wrong".toCharArray())
        );

        PGPSecretKey wrongSecretKey = wrongKeyRingGen.generateSecretKeyRing().getSecretKey();
        PGPPrivateKey wrongPrivateKey = wrongSecretKey.extractPrivateKey(
                new JcePBESecretKeyDecryptorBuilder()
                        .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                        .build("wrong".toCharArray())
        );

        // Try to decrypt with wrong key - should throw exception
        assertThrows(PGPEncryptionHandler.PGPException.class, () -> {
            handler.decrypt(encrypted, wrongPrivateKey);
        });
    }

    @Test
    void testEncryptNullData() {
        assertThrows(Exception.class, () -> {
            handler.encrypt(null, publicKey, false);
        });
    }

    @Test
    void testDecryptInvalidData() {
        byte[] invalidData = "This is not encrypted data".getBytes(StandardCharsets.UTF_8);

        assertThrows(PGPEncryptionHandler.PGPException.class, () -> {
            handler.decrypt(invalidData, privateKey);
        });
    }
}
