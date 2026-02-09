#!/bin/bash

# High-throughput demo: 1000 messages/second with minute-based file rotation
# Shows different PAN strategies with PGP encryption

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
DOCKER_COMPOSE_FILE="$PROJECT_DIR/docker-compose.yml"
UBER_JAR="$PROJECT_DIR/target/kafka-connect-banking-poc-1.0.0-SNAPSHOT-uber.jar"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

echo -e "${BLUE}================================================${NC}"
echo -e "${BLUE}  High-Throughput Demo: 1000 msg/sec${NC}"
echo -e "${BLUE}================================================${NC}"
echo ""
echo "This demo will:"
echo "  1. Start Kafka and MinIO services"
echo "  2. Deploy S3 connector with minute-based file rotation"
echo "  3. Send 1000 messages per second for demonstration"
echo "  4. Show different PAN strategies with PGP encryption"
echo ""
echo -e "${YELLOW}Configuration:${NC}"
echo "  - Rate: 1000 messages/second"
echo "  - File rotation: Every minute"
echo "  - Banks: BNK001, BNK002, BNK003, HSBC, BNPP, SOCGEN"
echo "  - PAN strategies: REMOVE, MASK, DECRYPT"
echo "  - All with PGP encryption enabled"
echo ""

# Step 1: Start Docker services
echo -e "${CYAN}[1/4] Starting Docker services...${NC}"
if [ ! -f "$DOCKER_COMPOSE_FILE" ]; then
    echo -e "${RED}Error: docker-compose.yml not found at $DOCKER_COMPOSE_FILE${NC}"
    exit 1
fi

docker-compose -f "$DOCKER_COMPOSE_FILE" down -v 2>/dev/null || true
docker-compose -f "$DOCKER_COMPOSE_FILE" up -d

echo "Waiting for Kafka to be ready..."
for i in {1..30}; do
    if docker exec kafka kafka-broker-api-versions.sh --bootstrap-server localhost:9092 >/dev/null 2>&1; then
        echo -e "${GREEN}✓ Kafka is ready${NC}"
        break
    fi
    echo "  Waiting... ($i/30)"
    sleep 1
done

echo "Waiting for MinIO to be ready..."
sleep 3

# Step 2: Verify topic exists
echo -e "${CYAN}[2/4] Verifying Kafka topic...${NC}"
docker exec kafka kafka-topics.sh --bootstrap-server localhost:9092 --list | grep -q payments-in && \
    echo -e "${GREEN}✓ Topic payments-in exists${NC}" || \
    (docker exec kafka kafka-topics.sh --bootstrap-server localhost:9092 --create --topic payments-in --partitions 3 --replication-factor 1 && \
    echo -e "${GREEN}✓ Created topic payments-in${NC}")

# Step 3: Deploy connector with minute-based rotation
echo -e "${CYAN}[3/4] Deploying S3 connector (minute-based rotation)...${NC}"
sleep 2

CONNECTOR_CONFIG='{
  "name": "s3-banking-sink-minute",
  "config": {
    "connector.class": "io.confluent.connect.s3.S3SinkConnector",
    "tasks.max": "1",
    "topics": "payments-in",
    "s3.bucket.name": "banking-payments",
    "s3.region": "us-east-1",
    "s3.part.size": "5242880",
    "flush.size": "1000000",
    "rotate.schedule.interval.ms": "60000",
    "rotate.interval.ms": "60000",
    "timezone": "UTC",
    "locale": "en_US",
    "storage.class": "io.confluent.connect.s3.storage.S3Storage",
    "format.class": "io.confluent.connect.s3.format.json.JsonFormat",
    "json.converter": "org.apache.kafka.connect.json.JsonConverter",
    "json.converter.schemas.enable": "false",
    "partitioner.class": "io.confluent.connect.storage.partitioners.FieldAndTimeBasedPartitioner",
    "partition.field.name": "X-Institution-Id",
    "path.format": "topics/payments/year=YYYY/month=MM/day=dd/hour=HH/minute=mm",
    "partition.duration.ms": "60000",
    "s3.object.content.type": "application/jsonl",
    "aws.access.key.id": "minioadmin",
    "aws.secret.access.key": "minioadmin",
    "store.kafka.headers": "false",
    "transforms": "headersToPayload,transformPAN,pgpEncryption",
    "transforms.headersToPayload.type": "com.banking.kafka.transforms.HeadersToPayloadTransform",
    "transforms.headersToPayload.mandatory.headers": "X-Institution-Id,X-Event-Type,X-Event-Version,X-Event-Id",
    "transforms.headersToPayload.optional.headers": "X-User-Id,X-Original-Correlation-Id,Original-Idempotency-Key",
    "transforms.headersToPayload.wrap.payload": "true",
    "transforms.transformPAN.type": "com.banking.kafka.transforms.PANTransformationSMT",
    "transforms.transformPAN.rules": "BNK001:*:*:MASK,BNK002:*:*:DECRYPT,BNK003:*:*:MASK,HSBC:*:*:MASK,BNPP:*:*:DECRYPT,SOCGEN:*:*:REMOVE",
    "transforms.transformPAN.default.strategy": "MASK",
    "transforms.transformPAN.mask.prefix.digits": "6",
    "transforms.transformPAN.mask.suffix.digits": "4",
    "transforms.pgpEncryption.type": "com.banking.kafka.transforms.PGPEncryptionSMT",
    "transforms.pgpEncryption.config.file": "/config/banks/bank-config.json",
    "transforms.pgpEncryption.institution.header": "X-Institution-Id",
    "transforms.pgpEncryption.event.type.header": "X-Event-Type",
    "transforms.pgpEncryption.event.version.header": "X-Event-Version"
  }
}'

curl -s -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d "$CONNECTOR_CONFIG" | jq . || echo -e "${YELLOW}Note: Connector might already exist${NC}"

sleep 2

if curl -s http://localhost:8083/connectors/s3-banking-sink-minute/status | jq .state | grep -q RUNNING; then
    echo -e "${GREEN}✓ Connector is running${NC}"
else
    echo -e "${YELLOW}⚠ Connector status check (may need a moment to start)${NC}"
fi

# Step 4: Run demo producer
echo -e "${CYAN}[4/4] Starting demo producer (1000 msg/sec)...${NC}"
echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  Demo is running!${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo "Messages sent to: ${CYAN}payments-in${NC}"
echo "Files created in MinIO: ${CYAN}banking-payments${NC} bucket"
echo "File rotation: Every minute"
echo ""
echo -e "${YELLOW}Monitor with:${NC}"
echo "  - View Kafka messages: docker exec kafka kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic payments-in --from-beginning --max-messages 10"
echo "  - MinIO UI: http://localhost:9001 (admin/minioadmin)"
echo "  - Kafka Connect UI: http://localhost:8083"
echo ""
echo -e "${YELLOW}Press Ctrl+C to stop the demo${NC}"
echo ""

# Run the demo producer
java -cp "$UBER_JAR" com.banking.kafka.demo.DemoProducer
