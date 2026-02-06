package com.banking.kafka.crypto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for JWEHandler
 */
class JWEHandlerTest {

    private JWEHandler jweHandler;
    private RSAPublicKey publicKey;
    private RSAPrivateKey privateKey;
    private RSAPublicKey partnerPublicKey;
    private RSAPrivateKey partnerPrivateKey;

    @BeforeEach
    void setUp() throws Exception {
        jweHandler = new JWEHandler();

        // Generate our key pair
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        KeyPair keyPair = keyGen.generateKeyPair();
        publicKey = (RSAPublicKey) keyPair.getPublic();
        privateKey = (RSAPrivateKey) keyPair.getPrivate();

        // Generate partner key pair (for rekey tests)
        KeyPair partnerKeyPair = keyGen.generateKeyPair();
        partnerPublicKey = (RSAPublicKey) partnerKeyPair.getPublic();
        partnerPrivateKey = (RSAPrivateKey) partnerKeyPair.getPrivate();
    }

    @Test
    void testEncryptDecrypt() throws Exception {
        String pan = "4532015112830366";

        // Encrypt
        String encrypted = jweHandler.encrypt(pan, publicKey);

        assertNotNull(encrypted);
        assertNotEquals(pan, encrypted);
        // JWE compact serialization has 5 parts separated by dots
        assertEquals(5, encrypted.split("\\.").length, "JWE should have 5 parts");

        // Decrypt
        String decrypted = jweHandler.decrypt(encrypted, privateKey);

        assertEquals(pan, decrypted);
    }

    @Test
    void testRekey() throws Exception {
        String pan = "4532015112830366";

        // Encrypt with our public key
        String encryptedWithOurKey = jweHandler.encrypt(pan, publicKey);

        // Rekey: decrypt with our private key, re-encrypt with partner's public key
        String rekeyedForPartner = jweHandler.rekey(encryptedWithOurKey, privateKey, partnerPublicKey);

        assertNotNull(rekeyedForPartner);
        assertNotEquals(encryptedWithOurKey, rekeyedForPartner);

        // Partner should be able to decrypt with their private key
        String decryptedByPartner = jweHandler.decrypt(rekeyedForPartner, partnerPrivateKey);
        assertEquals(pan, decryptedByPartner);

        // We should NOT be able to decrypt the rekeyed version with our key
        assertThrows(JWEHandler.JWEException.class, () -> {
            jweHandler.decrypt(rekeyedForPartner, privateKey);
        });
    }

    @Test
    void testEncryptNullPlaintext() {
        assertThrows(JWEHandler.JWEException.class, () -> {
            jweHandler.encrypt(null, publicKey);
        });
    }

    @Test
    void testEncryptEmptyPlaintext() {
        assertThrows(JWEHandler.JWEException.class, () -> {
            jweHandler.encrypt("", publicKey);
        });
    }

    @Test
    void testEncryptNullKey() {
        assertThrows(JWEHandler.JWEException.class, () -> {
            jweHandler.encrypt("4532015112830366", null);
        });
    }

    @Test
    void testDecryptNullJwe() {
        assertThrows(JWEHandler.JWEException.class, () -> {
            jweHandler.decrypt(null, privateKey);
        });
    }

    @Test
    void testDecryptEmptyJwe() {
        assertThrows(JWEHandler.JWEException.class, () -> {
            jweHandler.decrypt("", privateKey);
        });
    }

    @Test
    void testDecryptInvalidJwe() {
        assertThrows(JWEHandler.JWEException.class, () -> {
            jweHandler.decrypt("not-a-valid-jwe", privateKey);
        });
    }

    @Test
    void testDecryptNullKey() throws Exception {
        String encrypted = jweHandler.encrypt("4532015112830366", publicKey);

        assertThrows(JWEHandler.JWEException.class, () -> {
            jweHandler.decrypt(encrypted, null);
        });
    }

    @Test
    void testDecryptWithWrongKey() throws Exception {
        String pan = "4532015112830366";
        String encrypted = jweHandler.encrypt(pan, publicKey);

        // Try to decrypt with partner's key (should fail)
        assertThrows(JWEHandler.JWEException.class, () -> {
            jweHandler.decrypt(encrypted, partnerPrivateKey);
        });
    }

    @Test
    void testMultiplePANs() throws Exception {
        String[] pans = {
            "4532015112830366",
            "5425233430109903",
            "374245455400126",
            "6011000990139424"
        };

        for (String pan : pans) {
            String encrypted = jweHandler.encrypt(pan, publicKey);
            String decrypted = jweHandler.decrypt(encrypted, privateKey);
            assertEquals(pan, decrypted, "PAN should match after encrypt/decrypt cycle");
        }
    }
}
