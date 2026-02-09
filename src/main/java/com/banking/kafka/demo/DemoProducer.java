package com.banking.kafka.demo;

import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Demo Producer for live demonstrations.
 *
 * Continuously sends payment messages with varied:
 * - Bank codes (X-Institution-Id)
 * - Event types (X-Event-Type)
 * - Event versions (X-Event-Version)
 *
 * Run with: java -cp target/*-uber.jar com.banking.kafka.demo.DemoProducer
 *
 * Environment variables:
 * - KAFKA_BOOTSTRAP_SERVER (default: localhost:9092)
 * - KAFKA_TOPIC (default: payments-in)
 * - MESSAGE_DELAY_MS (default: 500)
 */
public class DemoProducer {

    // Bank codes
    private static final String[] BANKS = {"BNK001", "BNK002", "BNK003", "HSBC", "BNPP", "SOCGEN"};

    // Event types
    private static final String[] EVENT_TYPES = {"PAYMENT", "REFUND", "TRANSFER", "WITHDRAWAL", "DEPOSIT"};

    // Event versions
    private static final String[] VERSIONS = {"1.0", "1.1", "2.0"};

    // PAN prefixes (Visa, Mastercard, Amex, Discover)
    private static final String[] PAN_PREFIXES = {"4532", "5425", "3742", "6011", "4111"};

    private static final Random random = new Random();
    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static final AtomicLong messageCount = new AtomicLong(0);

    // ANSI colors
    private static final String GREEN = "\u001B[32m";
    private static final String BLUE = "\u001B[34m";
    private static final String YELLOW = "\u001B[33m";
    private static final String CYAN = "\u001B[36m";
    private static final String RESET = "\u001B[0m";

    public static void main(String[] args) {
        String bootstrapServer = System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVER", "localhost:9092");
        String topic = System.getenv().getOrDefault("KAFKA_TOPIC", "payments-in");
        int messagesPerSecond = Integer.parseInt(System.getenv().getOrDefault("MESSAGES_PER_SECOND", "1000"));

        printBanner();
        System.out.println("Bootstrap: " + BLUE + bootstrapServer + RESET);
        System.out.println("Topic: " + BLUE + topic + RESET);
        System.out.println("Rate: " + BLUE + messagesPerSecond + " msg/sec" + RESET);
        System.out.println(YELLOW + "Press Ctrl+C to stop" + RESET);
        System.out.println();

        // Handle shutdown gracefully
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running.set(false);
            try {
                Thread.sleep(2000); // Allow flush
            } catch (InterruptedException ignored) {}
            System.out.println("\n" + GREEN + "Stopped after " + messageCount.get() + " messages" + RESET);
        }));

        // Producer configuration with batching for high throughput
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServer);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer");
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer");
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 32 * 1024); // 32KB batches
        props.put(ProducerConfig.LINGER_MS_CONFIG, 10); // Wait up to 10ms to batch
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            long messagesPerBatch = Math.max(1, messagesPerSecond / 100); // Send in bursts
            long batchIntervalMs = 1000 / 100; // 100 bursts per second

            while (running.get()) {
                long batchStart = System.currentTimeMillis();

                // Send burst of messages
                for (int i = 0; i < messagesPerBatch && running.get(); i++) {
                    sendMessage(producer, topic);
                    messageCount.incrementAndGet();
                }

                // Regulate rate
                long elapsed = System.currentTimeMillis() - batchStart;
                if (elapsed < batchIntervalMs) {
                    Thread.sleep(batchIntervalMs - elapsed);
                }
            }

            // Final flush
            producer.flush();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void sendMessage(KafkaProducer<String, String> producer, String topic) {
        // Random selections
        String bank = BANKS[random.nextInt(BANKS.length)];
        String eventType = EVENT_TYPES[random.nextInt(EVENT_TYPES.length)];
        String version = VERSIONS[random.nextInt(VERSIONS.length)];

        // Generate IDs
        String eventId = UUID.randomUUID().toString();
        String txnId = "txn-" + System.currentTimeMillis() + "-" + messageCount.get();
        String userId = "user-" + random.nextInt(10000);
        double amount = random.nextInt(10000) + random.nextDouble();
        String pan = generatePAN();
        String timestamp = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format(new Date());

        // Create headers
        Headers headers = new RecordHeaders();
        headers.add("X-Institution-Id", bank.getBytes(StandardCharsets.UTF_8));
        headers.add("X-Event-Type", eventType.getBytes(StandardCharsets.UTF_8));
        headers.add("X-Event-Version", version.getBytes(StandardCharsets.UTF_8));
        headers.add("X-Event-Id", eventId.getBytes(StandardCharsets.UTF_8));
        headers.add("X-User-Id", userId.getBytes(StandardCharsets.UTF_8));
        headers.add("X-Original-Correlation-Id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));

        // Create payload
        String payload = String.format(
            "{\"transactionId\":\"%s\",\"amount\":%.2f,\"currency\":\"EUR\"," +
            "\"encryptedPrimaryAccountNumber\":\"%s\",\"merchantName\":\"Demo Merchant %d\"," +
            "\"timestamp\":\"%s\"}",
            txnId, amount, pan, random.nextInt(100), timestamp
        );

        // Send message
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, null, txnId, payload, headers);

        producer.send(record, (metadata, exception) -> {
            if (exception != null) {
                System.err.println("Error sending message: " + exception.getMessage());
            }
        });

        // Display progress
        String eventColor = getEventColor(eventType);
        System.out.printf("[%s%d%s] %s%-6s%s | %s%-10s%s | v%-3s | %s | %s%.2f%s%n",
            GREEN, messageCount.get(), RESET,
            CYAN, bank, RESET,
            eventColor, eventType, RESET,
            version, txnId,
            YELLOW, amount, RESET
        );
    }

    private static String generatePAN() {
        String prefix = PAN_PREFIXES[random.nextInt(PAN_PREFIXES.length)];
        StringBuilder pan = new StringBuilder(prefix);
        for (int i = 0; i < 12; i++) {
            pan.append(random.nextInt(10));
        }
        return pan.toString();
    }

    private static String getEventColor(String eventType) {
        switch (eventType) {
            case "PAYMENT": return GREEN;
            case "REFUND": return "\u001B[31m"; // Red
            case "TRANSFER": return BLUE;
            case "WITHDRAWAL": return YELLOW;
            case "DEPOSIT": return CYAN;
            default: return RESET;
        }
    }

    private static void printBanner() {
        System.out.println(GREEN + "========================================" + RESET);
        System.out.println(GREEN + "  Banking Kafka Demo Producer" + RESET);
        System.out.println(GREEN + "========================================" + RESET);
    }
}
