package com.banking.kafka.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.StringSerializer;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Future;

/**
 * Multi-bank test producer for banking payment messages.
 *
 * Generates test messages for different banks with different scenarios:
 * - BNK001: With encrypted PAN (will be REMOVED)
 * - BNK002: With encrypted PAN (will be DECRYPTED)
 * - BNK003: With encrypted PAN (will be REKEYED)
 * - BNK004: Without PAN field (NO transformation)
 * - BNK005: With encrypted PAN (will be DECRYPTED and tokenized)
 */
public class MultiBankPaymentProducer {

    private final KafkaProducer<String, String> producer;
    private final ObjectMapper mapper;
    private final String topic;

    // Simulated encrypted PAN (JWE format)
    private static final String MOCK_ENCRYPTED_PAN =
        "eyJhbGciOiJSU0EtT0FFUC0yNTYiLCJlbmMiOiJBMjU2R0NNIn0.MOCK_ENCRYPTED_PAN_DATA";

    public MultiBankPaymentProducer(String bootstrapServers, String topic) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);

        this.producer = new KafkaProducer<>(props);
        this.mapper = new ObjectMapper();
        this.topic = topic;

        System.out.println("Multi-Bank Producer initialized for topic: " + topic);
    }

    /**
     * Send payment for BNK001 (PAN will be REMOVED).
     */
    public Future<RecordMetadata> sendPaymentBNK001(String transactionId, double amount) {
        try {
            Map<String, Object> payment = new HashMap<>();
            payment.put("transactionId", transactionId);
            payment.put("amount", amount);
            payment.put("currency", "USD");
            payment.put("timestamp", System.currentTimeMillis());
            payment.put("encryptedPrimaryAccountNumber", MOCK_ENCRYPTED_PAN);
            payment.put("merchantId", "MERCHANT-BNK001");

            return sendPayment("BNK001", transactionId, payment,
                    "BNK001: Encrypted PAN (will be REMOVED for strict compliance)");

        } catch (Exception e) {
            throw new RuntimeException("Failed to send BNK001 payment", e);
        }
    }

    /**
     * Send payment for BNK002 (PAN will be DECRYPTED).
     */
    public Future<RecordMetadata> sendPaymentBNK002(String transactionId, double amount) {
        try {
            Map<String, Object> payment = new HashMap<>();
            payment.put("transactionId", transactionId);
            payment.put("amount", amount);
            payment.put("currency", "EUR");
            payment.put("timestamp", System.currentTimeMillis());
            payment.put("encryptedPrimaryAccountNumber", MOCK_ENCRYPTED_PAN);
            payment.put("merchantId", "MERCHANT-BNK002");
            payment.put("legacySystemId", "LEGACY-" + new Random().nextInt(1000));

            return sendPayment("BNK002", transactionId, payment,
                    "BNK002: Encrypted PAN (will be DECRYPTED for legacy system)");

        } catch (Exception e) {
            throw new RuntimeException("Failed to send BNK002 payment", e);
        }
    }

    /**
     * Send payment for BNK003 (PAN will be REKEYED).
     */
    public Future<RecordMetadata> sendPaymentBNK003(String transactionId, double amount) {
        try {
            Map<String, Object> payment = new HashMap<>();
            payment.put("transactionId", transactionId);
            payment.put("amount", amount);
            payment.put("currency", "GBP");
            payment.put("timestamp", System.currentTimeMillis());
            payment.put("encryptedPrimaryAccountNumber", MOCK_ENCRYPTED_PAN);
            payment.put("merchantId", "MERCHANT-BNK003");
            payment.put("internationalTransfer", true);

            return sendPayment("BNK003", transactionId, payment,
                    "BNK003: Encrypted PAN (will be REKEYED with bank-specific key)");

        } catch (Exception e) {
            throw new RuntimeException("Failed to send BNK003 payment", e);
        }
    }

    /**
     * Send payment for BNK004 (NO PAN field).
     */
    public Future<RecordMetadata> sendPaymentBNK004(String transactionId, double amount) {
        try {
            Map<String, Object> payment = new HashMap<>();
            payment.put("transactionId", transactionId);
            payment.put("amount", amount);
            payment.put("currency", "USD");
            payment.put("timestamp", System.currentTimeMillis());
            payment.put("accountToken", "TOKEN-" + UUID.randomUUID().toString().substring(0, 8));
            payment.put("merchantId", "MERCHANT-BNK004");

            return sendPayment("BNK004", transactionId, payment,
                    "BNK004: No PAN field (uses account tokens only)");

        } catch (Exception e) {
            throw new RuntimeException("Failed to send BNK004 payment", e);
        }
    }

    /**
     * Send payment for BNK005 (PAN will be DECRYPTED and TOKENIZED).
     */
    public Future<RecordMetadata> sendPaymentBNK005(String transactionId, double amount) {
        try {
            Map<String, Object> payment = new HashMap<>();
            payment.put("transactionId", transactionId);
            payment.put("amount", amount);
            payment.put("currency", "USD");
            payment.put("timestamp", System.currentTimeMillis());
            payment.put("encryptedPrimaryAccountNumber", MOCK_ENCRYPTED_PAN);
            payment.put("merchantId", "MERCHANT-BNK005");
            payment.put("digital", true);
            payment.put("securityLevel", "PCI-DSS-Level-1");

            return sendPayment("BNK005", transactionId, payment,
                    "BNK005: Encrypted PAN (will be DECRYPTED and TOKENIZED with double encryption)");

        } catch (Exception e) {
            throw new RuntimeException("Failed to send BNK005 payment", e);
        }
    }

    /**
     * Send payment with headers.
     */
    private Future<RecordMetadata> sendPayment(String bankCode, String transactionId,
                                                 Map<String, Object> payment, String description) {
        try {
            String value = mapper.writeValueAsString(payment);

            // Create headers
            Headers headers = new RecordHeaders();
            headers.add("X-Institution-Id", bankCode.getBytes(StandardCharsets.UTF_8));
            headers.add("X-Event-Type", "PAYMENT".getBytes(StandardCharsets.UTF_8));
            headers.add("X-Version", "1.0".getBytes(StandardCharsets.UTF_8));

            // Create record
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    topic,
                    null,
                    transactionId,
                    value,
                    headers
            );

            System.out.println("📤 " + description);

            return producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    System.err.println("❌ Error sending " + bankCode + ": " + exception.getMessage());
                } else {
                    System.out.println("✅ " + bankCode + " → partition " + metadata.partition() +
                            " offset " + metadata.offset());
                }
            });

        } catch (Exception e) {
            throw new RuntimeException("Failed to send payment", e);
        }
    }

    /**
     * Send test payments for all banks.
     */
    public void sendTestPaymentsAllBanks(int countPerBank) {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("📊 Multi-Bank Payment Test");
        System.out.println("=".repeat(70));
        System.out.println("Sending " + countPerBank + " payments per bank (5 banks total)");
        System.out.println("=".repeat(70) + "\n");

        Random random = new Random();

        for (int i = 0; i < countPerBank; i++) {
            String txnId = String.format("TXN-%03d", i + 1);

            // BNK001: REMOVE strategy
            sendPaymentBNK001(txnId + "-BNK001", 100.0 + random.nextDouble() * 900.0);
            sleep(50);

            // BNK002: DECRYPT strategy
            sendPaymentBNK002(txnId + "-BNK002", 100.0 + random.nextDouble() * 900.0);
            sleep(50);

            // BNK003: REKEY strategy
            sendPaymentBNK003(txnId + "-BNK003", 100.0 + random.nextDouble() * 900.0);
            sleep(50);

            // BNK004: NONE strategy (no PAN)
            sendPaymentBNK004(txnId + "-BNK004", 100.0 + random.nextDouble() * 900.0);
            sleep(50);

            // BNK005: DECRYPT + TOKENIZE strategy
            sendPaymentBNK005(txnId + "-BNK005", 100.0 + random.nextDouble() * 900.0);
            sleep(50);
        }

        System.out.println("\n" + "=".repeat(70));
        System.out.println("Flushing producer...");
        producer.flush();
        System.out.println("✅ All messages sent successfully!");
        System.out.println("=".repeat(70) + "\n");
    }

    /**
     * Send messages for a specific bank only.
     */
    public void sendTestPaymentsForBank(String bankCode, int count) {
        System.out.println("\nSending " + count + " payments for " + bankCode + "...\n");

        Random random = new Random();

        for (int i = 0; i < count; i++) {
            String txnId = String.format("TXN-%s-%03d", bankCode, i + 1);
            double amount = 100.0 + random.nextDouble() * 900.0;

            switch (bankCode) {
                case "BNK001":
                    sendPaymentBNK001(txnId, amount);
                    break;
                case "BNK002":
                    sendPaymentBNK002(txnId, amount);
                    break;
                case "BNK003":
                    sendPaymentBNK003(txnId, amount);
                    break;
                case "BNK004":
                    sendPaymentBNK004(txnId, amount);
                    break;
                case "BNK005":
                    sendPaymentBNK005(txnId, amount);
                    break;
                default:
                    System.err.println("Unknown bank: " + bankCode);
                    return;
            }

            sleep(100);
        }

        producer.flush();
        System.out.println("\n✅ " + count + " messages sent for " + bankCode + "\n");
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void close() {
        producer.close();
        System.out.println("Producer closed");
    }

    /**
     * Main method for standalone execution.
     *
     * Usage:
     * - All banks: java MultiBankPaymentProducer [servers] [topic] [count-per-bank]
     * - One bank: java MultiBankPaymentProducer [servers] [topic] [count] [bank-code]
     */
    public static void main(String[] args) {
        String bootstrapServers = args.length > 0 ? args[0] : "localhost:9092";
        String topic = args.length > 1 ? args[1] : "payments-in";
        int count = args.length > 2 ? Integer.parseInt(args[2]) : 5;
        String bankCode = args.length > 3 ? args[3] : null;

        MultiBankPaymentProducer producer = new MultiBankPaymentProducer(bootstrapServers, topic);

        try {
            if (bankCode != null) {
                // Send for specific bank
                producer.sendTestPaymentsForBank(bankCode.toUpperCase(), count);
            } else {
                // Send for all banks
                producer.sendTestPaymentsAllBanks(count);
            }
        } catch (Exception e) {
            System.err.println("Error during test: " + e.getMessage());
            e.printStackTrace();
        } finally {
            producer.close();
        }
    }
}
