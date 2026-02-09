# High-Throughput Demo: 1000 Messages/Second

This demo showcases the Banking Kafka Connect POC with high-throughput message processing, minute-based file rotation, and various PAN handling strategies with PGP encryption.

## Overview

**Demo Configuration:**
- **Throughput**: 1000 messages per second
- **File Rotation**: One file per minute (vs hourly in production)
- **Banks**: 6 different banks with varying configurations
- **Transforms**: Headers extraction → PAN transformation → PGP encryption
- **Output**: S3/MinIO storage with JSONL format

## Bank Configurations

### BNK001 - Banque Nationale
- **PAN Strategy**: MASK (shows first 6 + last 4 digits)
- **Format**: `453201******0366`
- **PGP Encryption**: Enabled (armored/ASCII format)
- **Rules**: All event types encrypted

### BNK002 - Crédit Populaire ⭐ (PAN Clear + Encrypted File)
- **PAN Strategy**: DECRYPT (PAN visible in plaintext)
- **Visibility**: PAN fully readable in JSON payload
- **PGP Encryption**: Enabled (armored/ASCII format)
- **Rules**: All event types encrypted
- **Use Case**: Legacy system that needs plaintext PAN but files are encrypted

### BNK003 - Banque Internationale
- **PAN Strategy**: MASK (shows first 6 + last 4 digits)
- **PGP Encryption**: Enabled (binary/non-armored for space optimization)
- **Rules**: All event types encrypted

### HSBC - HSBC Bank
- **PAN Strategy**: MASK (shows first 6 + last 4 digits)
- **PGP Encryption**: Enabled for PAYMENT events only
- **Rules**: PAYMENT events encrypted, others depend on default

### BNPP - BNP Paribas ⭐ (PAN Clear + Selective Encryption)
- **PAN Strategy**: DECRYPT (PAN visible in plaintext)
- **PGP Encryption**: Selective (rules-based)
- **Rules**:
  - PAYMENT events: Encrypt
  - REFUND events: Encrypt
  - TRANSFER events: Encrypt
  - Other events: Skip encryption
- **Use Case**: Shows rule-based encryption based on event type

### SOCGEN - Société Générale
- **PAN Strategy**: REMOVE (PAN completely removed)
- **PGP Encryption**: Enabled for all events
- **Rules**: All events encrypted
- **Use Case**: Maximum security - no PAN at all, plus encryption

## Running the Demo

### Quick Start

```bash
./scripts/run-high-throughput-demo.sh
```

The script will:
1. Start Kafka and MinIO services
2. Deploy the S3 connector with minute-based rotation
3. Start the demo producer sending 1000 messages/second

### Manual Setup

**Step 1: Start Services**
```bash
docker-compose up -d
```

**Step 2: Wait for Kafka**
```bash
# Verify Kafka is ready
docker exec kafka kafka-broker-api-versions.sh --bootstrap-server localhost:9092
```

**Step 3: Deploy Connector**
```bash
curl -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d @config/connectors/s3-sink-connector-minute.json
```

**Step 4: Run Producer**
```bash
java -cp target/kafka-connect-banking-poc-1.0.0-SNAPSHOT-uber.jar \
  com.banking.kafka.demo.DemoProducer
```

Or with custom settings:
```bash
KAFKA_BOOTSTRAP_SERVER=localhost:9092 \
KAFKA_TOPIC=payments-in \
MESSAGES_PER_SECOND=1000 \
  java -cp target/kafka-connect-banking-poc-1.0.0-SNAPSHOT-uber.jar \
  com.banking.kafka.demo.DemoProducer
```

## Output Structure

Files are created in MinIO with this structure:
```
banking-payments/
├── topics/payments/
│   ├── year=2024/
│   │   ├── month=02/
│   │   │   ├── day=09/
│   │   │   │   ├── hour=10/
│   │   │   │   │   ├── minute=15/
│   │   │   │   │   │   ├── BNK001+0+000000000000000.json (MASK)
│   │   │   │   │   │   ├── BNK002+0+000000000000001.json (DECRYPT + PGP)
│   │   │   │   │   │   ├── BNK003+0+000000000000002.json (MASK + PGP)
│   │   │   │   │   │   ├── HSBC+0+000000000000003.json (MASK + PGP)
│   │   │   │   │   │   ├── BNPP+0+000000000000004.json (DECRYPT + PGP selective)
│   │   │   │   │   │   └── SOCGEN+0+000000000000005.json (REMOVE + PGP)
```

Each file contains messages for the specific minute, rotated automatically.

## Monitoring

### View Kafka Messages
```bash
docker exec kafka kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic payments-in \
  --from-beginning \
  --max-messages 5
```

### Check MinIO Files
1. Open http://localhost:9001
2. Login: `admin` / `minioadmin`
3. Browse `banking-payments` bucket
4. Navigate to `topics/payments/year=.../month=.../day=.../hour=.../minute=.../`

### Monitor Connector Status
```bash
# List connectors
curl http://localhost:8083/connectors | jq .

# Check connector status
curl http://localhost:8083/connectors/s3-banking-sink-minute/status | jq .

# View connector tasks
curl http://localhost:8083/connectors/s3-banking-sink-minute/tasks | jq .
```

## Example Message Flow

### 1. Producer sends message for BNK002
```json
{
  "transactionId": "txn-1707460800000-42",
  "amount": 1234.56,
  "currency": "EUR",
  "encryptedPrimaryAccountNumber": "4532123456789123",
  "merchantName": "Demo Merchant 42",
  "timestamp": "2024-02-09T10:15:00Z"
}
```

Headers:
- `X-Institution-Id`: BNK002
- `X-Event-Type`: PAYMENT
- `X-Event-Version`: 1.0
- `X-Event-Id`: <uuid>

### 2. HeadersToPayloadTransform
Extracts headers and wraps payload:
```json
{
  "headers": {
    "X-Institution-Id": "BNK002",
    "X-Event-Type": "PAYMENT",
    "X-Event-Version": "1.0",
    "X-Event-Id": "..."
  },
  "payload": {
    "transactionId": "txn-1707460800000-42",
    "amount": 1234.56,
    "currency": "EUR",
    "encryptedPrimaryAccountNumber": "4532123456789123",
    "merchantName": "Demo Merchant 42",
    "timestamp": "2024-02-09T10:15:00Z"
  }
}
```

### 3. PANTransformationSMT (BNK002 = DECRYPT)
Decrypts PAN to plaintext (rule: BNK002:*:*:DECRYPT):
```json
{
  "headers": {
    "X-Institution-Id": "BNK002",
    "X-Event-Type": "PAYMENT",
    "X-Event-Version": "1.0",
    "X-Event-Id": "..."
  },
  "payload": {
    "transactionId": "txn-1707460800000-42",
    "amount": 1234.56,
    "currency": "EUR",
    "primaryAccountNumber": "4532123456789123",  // ← Decrypted (visible!)
    "merchantName": "Demo Merchant 42",
    "timestamp": "2024-02-09T10:15:00Z"
  }
}
```

### 4. PGPEncryptionSMT
Encrypts entire payload with PGP (BNK002 has encryption enabled + PAYMENT:*:ENCRYPT rule):
```
-----BEGIN PGP MESSAGE-----
Version: BCPG v1.77

wV4DULEqKLIYJVASAQdGR6A8JJXn4qL2eY9...
[... encrypted content ...]
-----END PGP MESSAGE-----
```

### 5. S3/MinIO Storage
File written to MinIO:
- **Path**: `banking-payments/topics/payments/year=2024/month=02/day=09/hour=10/minute=15/BNK002+0+000000000001.json`
- **Content**: Encrypted JSON (PGP armored)
- **Encryption**: PGP with BNK002's public key

## Configuration Comparison

| Bank | PAN Visible | Encryption | Rules | Use Case |
|------|------------|-----------|-------|----------|
| BNK001 | MASKED | Yes | All events | Standard processing |
| **BNK002** | **CLEAR ✓** | **Yes** | **All events** | **Legacy system with file encryption** |
| BNK003 | MASKED | Yes (binary) | All events | Space-optimized storage |
| HSBC | MASKED | Yes | PAYMENT only | Selective encryption |
| **BNPP** | **CLEAR ✓** | **Yes** | **By type** | **Flexible rules per event type** |
| SOCGEN | REMOVED | Yes | All events | Maximum security |

Stars (⭐) indicate the configurations demonstrating "PAN clear but file encrypted".

## Performance Metrics

During the demo, you'll see:
- **Throughput**: ~1000 messages/second
- **Files/minute**: 6 files (1 per bank partition)
- **Message latency**: < 100ms end-to-end
- **CPU/Memory**: Moderate (batching + compression)

## Common Issues

### Kafka not ready
```bash
# Check logs
docker logs kafka

# Restart if needed
docker-compose restart kafka
```

### Connector fails to deploy
```bash
# Check Kafka Connect logs
docker logs connect

# Check connector status
curl http://localhost:8083/connectors/s3-banking-sink-minute/status | jq .
```

### Files not appearing in MinIO
1. Check connector is running: `curl http://localhost:8083/connectors/s3-banking-sink-minute/status`
2. Check Kafka topic has messages: `docker exec kafka kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic payments-in`
3. Check S3 connector logs: `docker logs connect`

### Demo producer slow
Increase `MESSAGES_PER_SECOND`:
```bash
MESSAGES_PER_SECOND=2000 ./scripts/run-high-throughput-demo.sh
```

## Stopping the Demo

Press `Ctrl+C` in the producer terminal, then:

```bash
# Stop services
docker-compose down

# Remove volumes (optional)
docker-compose down -v
```

## Next Steps

After running the demo:

1. **Inspect encrypted files**: Download from MinIO and verify PGP encryption
2. **Test decryption**: Use PGP keys to decrypt messages from encrypted banks
3. **Scale testing**: Increase MESSAGES_PER_SECOND for stress testing
4. **Custom rules**: Modify bank-config.json to test different encryption rules
5. **Production config**: Switch to hourly rotation for production use

## References

- [Configuration Guide](CONFIG.md)
- [Architecture Overview](README.md)
- [Kafka Connect Documentation](https://docs.confluent.io/kafka-connect/)
