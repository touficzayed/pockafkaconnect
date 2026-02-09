#!/bin/bash
#
# Deploy S3 Sink Connector with hourly or minute granularity
#
# Usage:
#   ./scripts/deploy-connector.sh hourly   # 1 file per hour (default, production)
#   ./scripts/deploy-connector.sh minute   # 1 file per minute (for tests/demo)
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
CONNECT_URL="${KAFKA_CONNECT_URL:-http://localhost:8083}"

GRANULARITY="${1:-hourly}"

case "$GRANULARITY" in
    hourly|hour|h)
        CONFIG_FILE="$PROJECT_DIR/config/connectors/s3-sink-connector-hourly.json"
        echo "Deploying connector with HOURLY granularity (production)"
        echo "  - Path: {bank}/{event}/{version}/year/month/day/hour/"
        echo "  - 1 file per hour per partition"
        ;;
    minute|min|m)
        CONFIG_FILE="$PROJECT_DIR/config/connectors/s3-sink-connector-minute.json"
        echo "Deploying connector with MINUTE granularity (test/demo)"
        echo "  - Path: {bank}/{event}/{version}/year/month/day/hour/minute/"
        echo "  - 1 file per minute per partition"
        ;;
    *)
        echo "Usage: $0 [hourly|minute]"
        echo "  hourly - 1 file per hour (default, production)"
        echo "  minute - 1 file per minute (for tests/demo)"
        exit 1
        ;;
esac

echo ""
echo "Config file: $CONFIG_FILE"
echo "Connect URL: $CONNECT_URL"
echo ""

# Check if connector exists
if curl -s "$CONNECT_URL/connectors/banking-s3-sink" > /dev/null 2>&1; then
    echo "Deleting existing connector..."
    curl -s -X DELETE "$CONNECT_URL/connectors/banking-s3-sink"
    sleep 2
fi

# Deploy new connector
echo "Deploying connector..."
RESULT=$(curl -s -X POST "$CONNECT_URL/connectors" \
    -H "Content-Type: application/json" \
    -d @"$CONFIG_FILE")

if echo "$RESULT" | grep -q '"name":"banking-s3-sink"'; then
    echo ""
    echo "Connector deployed successfully!"
    echo ""

    # Wait and check status
    sleep 3
    echo "Status:"
    curl -s "$CONNECT_URL/connectors/banking-s3-sink/status" | python3 -m json.tool 2>/dev/null || echo "$RESULT"
else
    echo "Error deploying connector:"
    echo "$RESULT"
    exit 1
fi
