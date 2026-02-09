#!/bin/bash

# Simple demo producer script - just sends 1000 msg/sec to Kafka
# No connector deployment needed - shows the message generation

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
UBER_JAR="$PROJECT_DIR/target/kafka-connect-banking-poc-1.0.0-SNAPSHOT-uber.jar"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

echo -e "${BLUE}================================================${NC}"
echo -e "${BLUE}  Banking Kafka Demo Producer${NC}"
echo -e "${BLUE}  1000 messages/second${NC}"
echo -e "${BLUE}================================================${NC}"
echo ""

# Check if Docker services are running
echo -e "${CYAN}[1/3] Checking Docker services...${NC}"
if ! docker ps | grep -q banking-kafka; then
    echo -e "${YELLOW}Starting Docker services...${NC}"
    docker-compose -f "$PROJECT_DIR/docker/docker-compose.yml" up -d
    sleep 5
fi

if ! docker ps | grep -q banking-kafka; then
    echo -e "${RED}Error: Kafka container not running${NC}"
    exit 1
fi

echo -e "${GREEN}✓ Kafka is running${NC}"

# Wait for Kafka to be ready
echo -e "${CYAN}[2/3] Waiting for Kafka...${NC}"
for i in {1..60}; do
    if docker exec banking-kafka sh -c "python3 -c 'import socket; s = socket.socket(); s.connect((\"localhost\", 9092)); s.close()'" >/dev/null 2>&1; then
        echo -e "${GREEN}✓ Kafka is ready${NC}"
        break
    fi
    echo "  Waiting... ($i/60)"
    sleep 1
done

# Verify JAR exists
echo -e "${CYAN}[3/3] Starting demo producer...${NC}"
if [ ! -f "$UBER_JAR" ]; then
    echo -e "${RED}Error: JAR not found at $UBER_JAR${NC}"
    echo "Please run: mvn clean package -DskipTests"
    exit 1
fi

echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  Demo is running!${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo -e "Sending ${CYAN}1000 messages/second${NC} to ${CYAN}payments-in${NC} topic"
echo ""
echo -e "${YELLOW}Bank codes:${NC} BNK001, BNK002, BNK003, HSBC, BNPP, SOCGEN"
echo -e "${YELLOW}Event types:${NC} PAYMENT, REFUND, TRANSFER, WITHDRAWAL, DEPOSIT"
echo -e "${YELLOW}Versions:${NC} 1.0, 1.1, 2.0"
echo ""
echo "Each message includes:"
echo "  - Random bank code (X-Institution-Id header)"
echo "  - Random event type (X-Event-Type header)"
echo "  - Random version (X-Event-Version header)"
echo "  - Transaction ID and amount"
echo "  - Encrypted PAN (Primary Account Number)"
echo ""
echo -e "${YELLOW}View messages with:${NC}"
echo "  docker exec banking-kafka sh -c 'kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic payments-in --from-beginning --max-messages 10 2>/dev/null'"
echo ""
echo -e "${YELLOW}Press Ctrl+C to stop${NC}"
echo ""

# Run the producer
java -cp "$UBER_JAR" com.banking.kafka.demo.DemoProducer
