package com.banking.kafka.crypto;

import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.*;
import org.bouncycastle.openpgp.operator.jcajce.JcePGPDataEncryptorBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyKeyEncryptionMethodGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Date;

/**
 * Streaming PGP encryption wrapper for an OutputStream.
 *
 * Wraps a target OutputStream (e.g., S3 upload stream) with PGP encryption layers
 * so that data is encrypted on the fly without buffering the entire content in memory.
 *
 * The encryption pipeline is:
 * <pre>
 *   write() -> literal data -> compression (ZIP) -> PGP encryption (AES-256) -> [armor] -> target OutputStream
 * </pre>
 *
 * Usage:
 * <pre>
 * try (PGPOutputStreamWrapper pgpOut = new PGPOutputStreamWrapper(s3OutputStream, publicKey, true)) {
 *     pgpOut.write(record1Bytes);
 *     pgpOut.write(record2Bytes);
 *     // ... stream records one by one, no full-file buffering
 * }
 * </pre>
 *
 * This class is NOT thread-safe. Each instance should be used by a single writer.
 */
public class PGPOutputStreamWrapper extends OutputStream {

    private static final Logger log = LoggerFactory.getLogger(PGPOutputStreamWrapper.class);
    private static final int BUFFER_SIZE = 4096;

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private final OutputStream armorOrTargetOut;
    private final PGPEncryptedDataGenerator encryptedDataGenerator;
    private final OutputStream encryptedOut;
    private final PGPCompressedDataGenerator compressedDataGenerator;
    private final OutputStream compressedOut;
    private final PGPLiteralDataGenerator literalDataGenerator;
    private final OutputStream literalOut;
    private boolean closed = false;

    /**
     * Create a streaming PGP encryption wrapper.
     *
     * @param target The underlying output stream to write encrypted data to
     * @param publicKey The PGP public key to encrypt with
     * @param armor Whether to ASCII-armor the output
     * @throws IOException if stream setup fails
     */
    public PGPOutputStreamWrapper(OutputStream target, PGPPublicKey publicKey, boolean armor) throws IOException {
        try {
            // 1. Optional ASCII armor layer
            this.armorOrTargetOut = armor ? new ArmoredOutputStream(target) : target;

            // 2. PGP encrypted data layer
            this.encryptedDataGenerator = new PGPEncryptedDataGenerator(
                    new JcePGPDataEncryptorBuilder(PGPEncryptedData.AES_256)
                            .setWithIntegrityPacket(true)
                            .setSecureRandom(new SecureRandom())
                            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            );
            encryptedDataGenerator.addMethod(
                    new JcePublicKeyKeyEncryptionMethodGenerator(publicKey)
                            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            );
            this.encryptedOut = encryptedDataGenerator.open(armorOrTargetOut, new byte[BUFFER_SIZE]);

            // 3. Compression layer
            this.compressedDataGenerator = new PGPCompressedDataGenerator(PGPCompressedData.ZIP);
            this.compressedOut = compressedDataGenerator.open(encryptedOut);

            // 4. Literal data layer — use BINARY format, streaming (unknown length = 0)
            this.literalDataGenerator = new PGPLiteralDataGenerator();
            this.literalOut = literalDataGenerator.open(
                    compressedOut,
                    PGPLiteralData.BINARY,
                    "encrypted-data",
                    new Date(),
                    new byte[BUFFER_SIZE]
            );

            log.debug("PGP streaming encryption initialized (armor={})", armor);

        } catch (PGPException e) {
            throw new IOException("Failed to initialize PGP streaming encryption", e);
        }
    }

    @Override
    public void write(int b) throws IOException {
        literalOut.write(b);
    }

    @Override
    public void write(byte[] b) throws IOException {
        literalOut.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        literalOut.write(b, off, len);
    }

    @Override
    public void flush() throws IOException {
        literalOut.flush();
    }

    /**
     * Close the PGP streaming pipeline.
     * Closes all layers in reverse order: literal -> compressed -> encrypted -> armor -> target.
     */
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;

        try {
            literalOut.close();
        } catch (IOException e) {
            log.warn("Error closing literal data stream", e);
        }

        try {
            compressedDataGenerator.close();
        } catch (IOException e) {
            log.warn("Error closing compressed stream", e);
        }

        try {
            encryptedOut.close();
        } catch (IOException e) {
            log.warn("Error closing encrypted stream", e);
        }

        try {
            armorOrTargetOut.close();
        } catch (IOException e) {
            log.warn("Error closing armor/target stream", e);
        }

        log.debug("PGP streaming encryption closed");
    }
}
