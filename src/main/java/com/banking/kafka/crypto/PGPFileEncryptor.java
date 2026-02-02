package com.banking.kafka.crypto;

import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Utility class for encrypting/decrypting files with PGP.
 *
 * This class provides file-level PGP operations for the Banking POC,
 * allowing JSONL files in S3/MinIO to be encrypted with PGP for an
 * additional layer of security.
 *
 * Usage:
 * <pre>
 * PGPFileEncryptor encryptor = new PGPFileEncryptor();
 * encryptor.encryptFile(inputFile, outputFile, publicKeyFile);
 * encryptor.decryptFile(encryptedFile, decryptedFile, privateKeyFile, passphrase);
 * </pre>
 *
 * Integration with S3 Sink Connector:
 * - Use as a post-processing step after S3 Sink writes files
 * - Can be integrated via custom storage class or external processor
 * - Recommended: Use S3 event triggers to encrypt uploaded files
 */
public class PGPFileEncryptor {

    private static final Logger log = LoggerFactory.getLogger(PGPFileEncryptor.class);

    private final PGPEncryptionHandler handler;

    public PGPFileEncryptor() {
        this.handler = new PGPEncryptionHandler();
    }

    /**
     * Encrypt a file using PGP public key.
     *
     * @param inputFile Path to the input file
     * @param outputFile Path to the encrypted output file
     * @param publicKeyFile Path to the PGP public key file
     * @param armor Whether to use ASCII armor
     * @throws PGPEncryptionHandler.PGPException if encryption fails
     */
    public void encryptFile(String inputFile, String outputFile, String publicKeyFile, boolean armor)
            throws PGPEncryptionHandler.PGPException {

        log.info("Encrypting file {} to {}", inputFile, outputFile);

        try {
            // Load public key
            PGPPublicKey publicKey = handler.loadPublicKey(publicKeyFile);

            // Read input file
            byte[] data = Files.readAllBytes(Paths.get(inputFile));
            log.debug("Read {} bytes from input file", data.length);

            // Encrypt data
            byte[] encrypted = handler.encrypt(data, publicKey, armor);
            log.debug("Encrypted to {} bytes", encrypted.length);

            // Write encrypted data
            Files.write(Paths.get(outputFile), encrypted);
            log.info("Successfully encrypted file to {}", outputFile);

        } catch (IOException e) {
            throw new PGPEncryptionHandler.PGPException("Failed to read/write file", e);
        }
    }

    /**
     * Decrypt a file using PGP private key.
     *
     * @param encryptedFile Path to the encrypted input file
     * @param outputFile Path to the decrypted output file
     * @param privateKeyFile Path to the PGP private key file
     * @param passphrase Passphrase to unlock the private key
     * @throws PGPEncryptionHandler.PGPException if decryption fails
     */
    public void decryptFile(String encryptedFile, String outputFile, String privateKeyFile, String passphrase)
            throws PGPEncryptionHandler.PGPException {

        log.info("Decrypting file {} to {}", encryptedFile, outputFile);

        try {
            // Load private key
            PGPPrivateKey privateKey = handler.loadPrivateKey(privateKeyFile, passphrase);

            // Read encrypted file
            byte[] encrypted = Files.readAllBytes(Paths.get(encryptedFile));
            log.debug("Read {} bytes from encrypted file", encrypted.length);

            // Decrypt data
            byte[] decrypted = handler.decrypt(encrypted, privateKey);
            log.debug("Decrypted to {} bytes", decrypted.length);

            // Write decrypted data
            Files.write(Paths.get(outputFile), decrypted);
            log.info("Successfully decrypted file to {}", outputFile);

        } catch (IOException e) {
            throw new PGPEncryptionHandler.PGPException("Failed to read/write file", e);
        }
    }

    /**
     * Encrypt a file in-place (replaces original with encrypted version).
     *
     * @param file Path to the file
     * @param publicKeyFile Path to the PGP public key file
     * @param armor Whether to use ASCII armor
     * @throws PGPEncryptionHandler.PGPException if encryption fails
     */
    public void encryptFileInPlace(String file, String publicKeyFile, boolean armor)
            throws PGPEncryptionHandler.PGPException {

        String tempFile = file + ".tmp";
        encryptFile(file, tempFile, publicKeyFile, armor);

        try {
            // Replace original with encrypted
            Files.delete(Paths.get(file));
            Files.move(Paths.get(tempFile), Paths.get(file));
            log.info("Replaced {} with encrypted version", file);
        } catch (IOException e) {
            throw new PGPEncryptionHandler.PGPException("Failed to replace file", e);
        }
    }

    /**
     * Batch encrypt all files in a directory.
     *
     * @param directory Path to the directory
     * @param publicKeyFile Path to the PGP public key file
     * @param pattern File pattern (e.g., "*.jsonl")
     * @param armor Whether to use ASCII armor
     * @return Number of files encrypted
     * @throws PGPEncryptionHandler.PGPException if encryption fails
     */
    public int encryptDirectory(String directory, String publicKeyFile, String pattern, boolean armor)
            throws PGPEncryptionHandler.PGPException {

        log.info("Encrypting files in directory {} matching pattern {}", directory, pattern);

        try {
            Path dir = Paths.get(directory);

            int count = 0;

            try (var stream = Files.walk(dir, 1)) {
                for (Path path : (Iterable<Path>) stream::iterator) {
                    if (Files.isRegularFile(path) && path.toString().matches(".*" + pattern.replace("*", ".*"))) {
                        String inputFile = path.toString();
                        String outputFile = inputFile + ".pgp";
                        encryptFile(inputFile, outputFile, publicKeyFile, armor);
                        count++;
                    }
                }
            }

            log.info("Encrypted {} files in directory {}", count, directory);
            return count;

        } catch (IOException e) {
            throw new PGPEncryptionHandler.PGPException("Failed to process directory", e);
        }
    }

    /**
     * Main method for command-line usage.
     *
     * Usage:
     * - Encrypt: java PGPFileEncryptor encrypt <input> <output> <public-key> [--armor]
     * - Decrypt: java PGPFileEncryptor decrypt <encrypted> <output> <private-key> <passphrase>
     */
    public static void main(String[] args) {
        if (args.length < 4) {
            System.err.println("Usage:");
            System.err.println("  Encrypt: encrypt <input> <output> <public-key> [--armor]");
            System.err.println("  Decrypt: decrypt <encrypted> <output> <private-key> <passphrase>");
            System.exit(1);
        }

        PGPFileEncryptor encryptor = new PGPFileEncryptor();

        try {
            String command = args[0];

            if ("encrypt".equals(command)) {
                String input = args[1];
                String output = args[2];
                String publicKey = args[3];
                boolean armor = args.length > 4 && "--armor".equals(args[4]);

                encryptor.encryptFile(input, output, publicKey, armor);
                System.out.println("Successfully encrypted " + input + " to " + output);

            } else if ("decrypt".equals(command)) {
                String encrypted = args[1];
                String output = args[2];
                String privateKey = args[3];
                String passphrase = args[4];

                encryptor.decryptFile(encrypted, output, privateKey, passphrase);
                System.out.println("Successfully decrypted " + encrypted + " to " + output);

            } else {
                System.err.println("Unknown command: " + command);
                System.exit(1);
            }

        } catch (PGPEncryptionHandler.PGPException e) {
            System.err.println("PGP operation failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
