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
 * Test producer for banking payment messages.
 *
 * Sends payment messages to Kafka with appropriate headers for testing
 * the complete pipeline including:
 * - Custom headers (institution ID, event type, etc.)
 * - Encrypted PAN data
 * - Partition routing via BankingHierarchicalPartitioner
 */
public class BankingPaymentProducer {

    private final KafkaProducer<String, String> producer;
    private final ObjectMapper mapper;
    private final String topic;

    public BankingPaymentProducer(String bootstrapServers, String topic) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 1);

        // Optional: Use custom partitioner
        // props.put(ProducerConfig.PARTITIONER_CLASS_CONFIG,
        //         "com.banking.kafka.partitioner.BankingHierarchicalPartitioner");

        this.producer = new KafkaProducer<>(props);
        this.mapper = new ObjectMapper();
        this.topic = topic;

        System.out.println("Producer initialized for topic: " + topic);
    }

    /**
     * Send a payment message with headers.
     *
     * @param institutionId Banking institution ID
     * @param transactionId Unique transaction ID
     * @param amount Transaction amount
     * @param encryptedPAN Encrypted PAN (or mock value for testing)
     * @return RecordMetadata future
     */
    public Future<RecordMetadata> sendPayment(String institutionId, String transactionId,
                                                double amount, String encryptedPAN) {
        try {
            // Create payment data
            Map<String, Object> payment = new HashMap<>();
            payment.put("transactionId", transactionId);
            payment.put("amount", amount);
            payment.put("currency", "USD");
            payment.put("timestamp", System.currentTimeMillis());
            payment.put("encryptedPrimaryAccountNumber", encryptedPAN);
            payment.put("merchantId", "MERCHANT-" + new Random().nextInt(1000));

            String value = mapper.writeValueAsString(payment);

            // Create headers
            Headers headers = new RecordHeaders();
            headers.add("X-Institution-Id", institutionId.getBytes(StandardCharsets.UTF_8));
            headers.add("X-Event-Type", "PAYMENT".getBytes(StandardCharsets.UTF_8));
            headers.add("X-Version", "1.0".getBytes(StandardCharsets.UTF_8));
            headers.add("X-User-Id", ("user-" + new Random().nextInt(10000)).getBytes(StandardCharsets.UTF_8));

            // Create record
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    topic,
                    null, // partition will be determined by partitioner
                    transactionId,
                    value,
                    headers
            );

            System.out.println("Sending payment: " + transactionId +
                    " for institution: " + institutionId +
                    " amount: $" + amount);

            return producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    System.err.println("Error sending message: " + exception.getMessage());
                    exception.printStackTrace();
                } else {
                    System.out.println("Message sent successfully to partition " +
                            metadata.partition() + " offset " + metadata.offset());
                }
            });

        } catch (Exception e) {
            throw new RuntimeException("Failed to send payment", e);
        }
    }

    /**
     * Send multiple test payments for different institutions.
     */
    public void sendTestPayments(int count) {
        String[] institutions = {"BNK001", "BNK002", "BNK003", "BNK004", "BNK005"};
        Random random = new Random();

        System.out.println("\nSending " + count + " test payments...\n");

        for (int i = 0; i < count; i++) {
            String institutionId = institutions[random.nextInt(institutions.length)];
            String transactionId = "TXN-" + UUID.randomUUID().toString();
            double amount = 10.0 + (random.nextDouble() * 1000.0);
            String encryptedPAN = "eyJhbGciOiJSU0EtT0FFUC0yNTYiLCJlbmMiOiJBMjU2R0NNIn0..."; // Mock encrypted PAN

            sendPayment(institutionId, transactionId, amount, encryptedPAN);

            // Small delay between messages
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        System.out.println("\nAll messages sent. Flushing producer...\n");
        producer.flush();
    }

    /**
     * Close the producer.
     */
    public void close() {
        producer.close();
        System.out.println("Producer closed");
    }

    /**
     * Main method for standalone execution.
     *
     * Usage: java BankingPaymentProducer [bootstrap-servers] [topic] [message-count]
     */
    public static void main(String[] args) {
        String bootstrapServers = args.length > 0 ? args[0] : "localhost:9092";
        String topic = args.length > 1 ? args[1] : "payments-in";
        int messageCount = args.length > 2 ? Integer.parseInt(args[2]) : 10;

        System.out.println("=".repeat(60));
        System.out.println("Banking Payment Producer");
        System.out.println("=".repeat(60));
        System.out.println("Bootstrap Servers: " + bootstrapServers);
        System.out.println("Topic: " + topic);
        System.out.println("Message Count: " + messageCount);
        System.out.println("=".repeat(60));

        BankingPaymentProducer producer = new BankingPaymentProducer(bootstrapServers, topic);

        try {
            producer.sendTestPayments(messageCount);
            System.out.println("\nTest completed successfully!");
        } catch (Exception e) {
            System.err.println("Error during test: " + e.getMessage());
            e.printStackTrace();
        } finally {
            producer.close();
        }
    }
}
