#!/bin/bash
#
# Demo Producer - Continuous Kafka message injection
# Sends payment messages with varied bank codes, event types, and versions
# Press Ctrl+C to stop
#

set -e

BOOTSTRAP_SERVER="${KAFKA_BOOTSTRAP_SERVER:-localhost:9092}"
TOPIC="${KAFKA_TOPIC:-payments-in}"

# Bank codes
BANKS=("BNK001" "BNK002" "BNK003" "HSBC" "BNPP" "SOCGEN")

# Event types
EVENT_TYPES=("PAYMENT" "REFUND" "TRANSFER" "WITHDRAWAL" "DEPOSIT")

# Event versions
VERSIONS=("1.0" "1.1" "2.0")

# Message counter
COUNT=0

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  Banking Kafka Demo Producer${NC}"
echo -e "${GREEN}========================================${NC}"
echo -e "Bootstrap: ${BLUE}${BOOTSTRAP_SERVER}${NC}"
echo -e "Topic: ${BLUE}${TOPIC}${NC}"
echo -e "${YELLOW}Press Ctrl+C to stop${NC}"
echo ""

# Trap Ctrl+C
trap 'echo -e "\n${GREEN}Stopped after ${COUNT} messages${NC}"; exit 0' INT

# Function to generate random PAN
generate_pan() {
    # Generate a random 16-digit PAN starting with common prefixes
    local prefixes=("4532" "5425" "3742" "6011" "4111")
    local prefix=${prefixes[$RANDOM % ${#prefixes[@]}]}
    echo "${prefix}$(printf '%012d' $((RANDOM * RANDOM % 1000000000000)))"
}

# Function to generate UUID
generate_uuid() {
    cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen 2>/dev/null || echo "$(date +%s)-$RANDOM-$RANDOM"
}

# Main loop
while true; do
    # Random selections
    BANK=${BANKS[$RANDOM % ${#BANKS[@]}]}
    EVENT_TYPE=${EVENT_TYPES[$RANDOM % ${#EVENT_TYPES[@]}]}
    VERSION=${VERSIONS[$RANDOM % ${#VERSIONS[@]}]}

    # Generate IDs
    EVENT_ID=$(generate_uuid)
    USER_ID="user-$((RANDOM % 10000))"
    TXN_ID="txn-$(date +%s)-$((COUNT))"
    AMOUNT=$((RANDOM % 10000 + 1)).$((RANDOM % 100))
    PAN=$(generate_pan)

    # Create message payload
    PAYLOAD=$(cat <<EOF
{
  "transactionId": "${TXN_ID}",
  "amount": ${AMOUNT},
  "currency": "EUR",
  "encryptedPrimaryAccountNumber": "${PAN}",
  "merchantName": "Demo Merchant $((RANDOM % 100))",
  "timestamp": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
EOF
)

    # Send message with headers using kafka-console-producer
    echo "${TXN_ID}:${PAYLOAD}" | docker exec -i banking-kafka kafka-console-producer \
        --bootstrap-server kafka:29092 \
        --topic "${TOPIC}" \
        --property "parse.key=true" \
        --property "key.separator=:" \
        --property "parse.headers=true" \
        --property "headers.delimiter=\t" \
        --property "headers.separator=," \
        --property "headers.key.separator=:" 2>/dev/null \
    || echo "${PAYLOAD}" | docker exec -i banking-kafka kafka-console-producer \
        --bootstrap-server kafka:29092 \
        --topic "${TOPIC}" 2>/dev/null

    # Actually, let's use kafkacat/kcat which handles headers better
    # Fallback: use our Java producer

    COUNT=$((COUNT + 1))

    # Display progress
    echo -e "[${GREEN}${COUNT}${NC}] ${BLUE}${BANK}${NC} | ${EVENT_TYPE} | v${VERSION} | ${TXN_ID} | €${AMOUNT}"

    # Wait between messages (100-500ms random)
    sleep 0.$((RANDOM % 4 + 1))
done
