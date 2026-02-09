#!/bin/bash

# Deploy Kafka Connect S3 Sink with PGP Transform

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$SCRIPT_DIR"

CONNECT_HOST="http://localhost:8083"
CONNECT_NAME="s3-banking-sink-pgp"

echo "======================================================================"
echo "  Kafka Connect S3 Sink Deployment (with PGP Encryption)"
echo "======================================================================"
echo ""

# Wait for Kafka Connect to be ready
echo "Waiting for Kafka Connect to be ready..."
for i in {1..60}; do
    if curl -s "$CONNECT_HOST/connectors" > /dev/null 2>&1; then
        echo "✓ Kafka Connect is ready"
        break
    fi
    if [ $i -eq 60 ]; then
        echo "✗ Kafka Connect did not start in time"
        exit 1
    fi
    echo -n "."
    sleep 1
done

echo ""

# Check if connector already exists
echo "Checking for existing connector..."
if curl -s "$CONNECT_HOST/connectors/$CONNECT_NAME" > /dev/null 2>&1; then
    echo "Connector '$CONNECT_NAME' already exists. Deleting..."
    curl -X DELETE "$CONNECT_HOST/connectors/$CONNECT_NAME"
    sleep 2
fi

echo ""
echo "Deploying S3 Sink Connector with PGP Transform..."
echo ""

# Deploy connector
RESPONSE=$(curl -X POST "$CONNECT_HOST/connectors" \
  -H "Content-Type: application/json" \
  -d @- << 'EOF'
{
  "name": "s3-banking-sink-pgp",
  "config": {
    "connector.class": "io.confluent.connect.s3.S3SinkConnector",
    "tasks.max": "1",
    "topics": "payments-in",

    "s3.bucket.name": "banking-payments",
    "s3.region": "us-east-1",
    "s3.endpoint.url": "http://banking-minio:9000",

    "aws.access.key.id": "minioadmin",
    "aws.secret.access.key": "minioadmin",

    "storage.class": "io.confluent.connect.s3.storage.S3Storage",
    "format.class": "io.confluent.connect.s3.format.json.JsonFormat",

    "json.converter": "org.apache.kafka.connect.json.JsonConverter",
    "json.converter.schemas.enable": "false",

    "s3.object.content.type": "application/jsonl",
    "flush.size": "100",
    "rotate.schedule.interval.ms": "60000",
    "timezone": "UTC",
    "path.format": "bank=year=YYYY/month=MM/day=dd/hour=HH/minute=mm",
    "partition.duration.ms": "60000",

    "store.kafka.headers": "true",

    "transforms": "pgpEncrypt",
    "transforms.pgpEncrypt.type": "com.banking.kafka.transforms.PGPEncryptionSMT",
    "transforms.pgpEncrypt.config.file": "/config/banks/bank-config.json",

    "errors.deadletterqueue.enable": "false",
    "errors.log.enable": "true",
    "errors.tolerance": "all"
  }
}
EOF
)

echo "$RESPONSE" | jq . 2>/dev/null || echo "$RESPONSE"

if echo "$RESPONSE" | grep -q "error_code"; then
    echo ""
    echo "✗ Connector deployment failed"
    exit 1
fi

echo ""
echo "✓ Connector deployed successfully"

# Wait a moment for connector to start
sleep 3

# Check connector status
echo ""
echo "Checking connector status..."
STATUS=$(curl -s "$CONNECT_HOST/connectors/$CONNECT_NAME/status" | jq '.connector.state' -r)

if [ "$STATUS" = "RUNNING" ]; then
    echo "✓ Connector is RUNNING"
else
    echo "⚠ Connector status: $STATUS"
fi

echo ""
echo "======================================================================"
echo "  Deployment Complete"
echo "======================================================================"
echo ""
echo "Connector Details:"
echo "  Name: $CONNECT_NAME"
echo "  URL: $CONNECT_HOST/connectors/$CONNECT_NAME"
echo ""
echo "Status URL: $CONNECT_HOST/connectors/$CONNECT_NAME/status"
echo "Config URL: $CONNECT_HOST/connectors/$CONNECT_NAME/config"
echo ""
echo "To view connector logs:"
echo "  docker logs banking-kafka-connect -f"
echo ""
echo "To delete connector:"
echo "  curl -X DELETE $CONNECT_HOST/connectors/$CONNECT_NAME"
echo ""
