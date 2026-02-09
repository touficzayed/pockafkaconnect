#!/bin/bash

# Complete Banking Kafka Connect Demo
# Starts producer and consumer together

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$SCRIPT_DIR"

echo "======================================================================"
echo "  Banking Kafka Connect - Complete Demo"
echo "======================================================================"
echo ""

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Check if services are running
echo -e "${BLUE}Checking Docker services...${NC}"
docker compose -f docker/docker-compose.yml ps | head -5 || {
    echo -e "${YELLOW}Starting Docker services...${NC}"
    docker compose -f docker/docker-compose.yml up -d
    sleep 10
}

# Check if JAR is built
if [ ! -f "target/kafka-connect-banking-poc-1.0.0-SNAPSHOT-jar-with-dependencies.jar" ]; then
    echo -e "${YELLOW}Building project...${NC}"
    mvn clean package -q
fi

# Check Python environment
if ! python3 -c "from kafka import KafkaConsumer; import boto3" 2>/dev/null; then
    echo -e "${YELLOW}Installing Python dependencies...${NC}"
    python3 -m pip install -q kafka-python boto3
fi

echo ""
echo -e "${BLUE}Starting Kafka Producer...${NC}"
timeout 45 java -cp "target/kafka-connect-banking-poc-1.0.0-SNAPSHOT-jar-with-dependencies.jar" \
    com.banking.kafka.demo.DemoProducer > /tmp/producer.log 2>&1 &
PRODUCER_PID=$!

echo "Producer PID: $PRODUCER_PID"
echo "Waiting 10 seconds for messages to accumulate..."
sleep 10

echo ""
echo -e "${BLUE}Starting Kafka Consumer (uploading to MinIO)...${NC}"
python3 scripts/kafka-consumer-to-minio.py

# Wait for producer
wait $PRODUCER_PID 2>/dev/null || true

echo ""
echo -e "${GREEN}======================================================================"
echo "  Demo Complete!"
echo "======================================================================"
echo ""
echo "MinIO bucket: banking-payments"
echo "Access URL: http://localhost:9000"
echo ""
echo "View files:"
python3 << 'EOF'
import boto3

s3 = boto3.client(
    's3',
    endpoint_url='http://localhost:9000',
    aws_access_key_id='minioadmin',
    aws_secret_access_key='minioadmin',
    region_name='us-east-1'
)

response = s3.list_objects_v2(
    Bucket='banking-payments',
    Prefix='messages/'
)

if 'Contents' in response:
    objects = response['Contents']
    print(f"  Total files: {len(objects)}")

    total_size = sum(obj['Size'] for obj in objects)
    print(f"  Total size: {total_size:,} bytes ({total_size/1024/1024:.2f} MB)")

    print("\n  Sample files:")
    for obj in sorted(objects, key=lambda x: x['Key'])[-5:]:
        print(f"    • {obj['Key']}")

EOF

echo ""
echo -e "${GREEN}✓ All done!${NC}"
