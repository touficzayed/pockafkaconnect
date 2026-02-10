#!/bin/bash
#
# Run the Demo Producer
#
# Usage: ./scripts/run-demo.sh [delay_ms]
#
# Example:
#   ./scripts/run-demo.sh        # Default 500ms delay
#   ./scripts/run-demo.sh 200    # Faster: 200ms delay
#   ./scripts/run-demo.sh 1000   # Slower: 1 second delay
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"


cd "$PROJECT_DIR"

# Build if needed
if [ ! -f "target/kafka-connect-banking-poc-1.0.0-SNAPSHOT-uber.jar" ]; then
    echo "Building project..."
    mvn clean package -DskipTests -q
fi

# Set environment
export KAFKA_BOOTSTRAP_SERVER="${KAFKA_BOOTSTRAP_SERVER:-localhost:9092}"
export KAFKA_TOPIC="${KAFKA_TOPIC:-payments-in}"
export MESSAGE_DELAY_MS="${1:-500}"

echo ""
echo "Starting Demo Producer..."
echo "  - Bootstrap: $KAFKA_BOOTSTRAP_SERVER"
echo "  - Topic: $KAFKA_TOPIC"
echo "  - Delay: ${MESSAGE_DELAY_MS}ms"
echo ""

# Run the demo producer
java -cp "target/kafka-connect-banking-poc-1.0.0-SNAPSHOT-uber.jar" \
    com.banking.kafka.demo.DemoProducer
