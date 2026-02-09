#!/bin/bash

# Complete Kafka Connect Demo with PGP Encryption

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$SCRIPT_DIR"

echo "======================================================================"
echo "  Banking Kafka Connect POC - Full Demo"
echo "  (Producer → Kafka → Kafka Connect + PGP Transform → MinIO)"
echo "======================================================================"
echo ""

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Start services
echo -e "${BLUE}1. Starting Docker services...${NC}"
docker compose -f docker/docker-compose.yml up -d
sleep 5
echo "✓ Services started"
echo ""

# Build project if needed
if [ ! -f "target/kafka-connect-banking-poc-1.0.0-SNAPSHOT-jar-with-dependencies.jar" ]; then
    echo -e "${BLUE}2. Building project...${NC}"
    mvn clean package -q
    echo "✓ Build complete"
    echo ""
fi

# Deploy Kafka Connect S3 Sink
echo -e "${BLUE}2. Deploying Kafka Connect S3 Sink with PGP Transform...${NC}"
bash scripts/deploy-kafka-connect-s3.sh
echo ""

# Give connector time to be ready
echo -e "${YELLOW}Waiting for connector to be ready...${NC}"
sleep 10

# Start producer
echo -e "${BLUE}3. Starting Demo Producer (1000 msg/sec)...${NC}"
timeout 60 java -cp "target/kafka-connect-banking-poc-1.0.0-SNAPSHOT-jar-with-dependencies.jar" \
    com.banking.kafka.demo.DemoProducer 2>&1 | tail -20 &
PRODUCER_PID=$!

echo "Producer PID: $PRODUCER_PID"
sleep 45

echo ""
echo -e "${BLUE}4. Results from MinIO...${NC}"
echo ""

python3 << 'PYEOF'
import boto3
import json

s3 = boto3.client(
    's3',
    endpoint_url='http://localhost:9000',
    aws_access_key_id='minioadmin',
    aws_secret_access_key='minioadmin',
    region_name='us-east-1'
)

response = s3.list_objects_v2(Bucket='banking-payments', Prefix='bank=')

if 'Contents' in response:
    objects = response['Contents']
    print(f"✓ Total files: {len(objects)}")

    total_size = sum(obj['Size'] for obj in objects)
    print(f"✓ Total size: {total_size:,} bytes ({total_size/1024/1024:.2f} MB)")

    print("\n✓ Sample files:")
    for obj in sorted(objects, key=lambda x: x['Key'])[-10:]:
        print(f"  • {obj['Key']} ({obj['Size']:,} bytes)")

    print("\n✓ File Organization (by bank/time):")
    prefixes = {}
    for obj in objects:
        prefix = obj['Key'].split('/')[0:2]
        prefix_key = '/'.join(prefix)
        if prefix_key not in prefixes:
            prefixes[prefix_key] = 0
        prefixes[prefix_key] += 1

    for prefix in sorted(prefixes.keys()):
        print(f"  • {prefix}/ → {prefixes[prefix]} files")

else:
    print("No files found in MinIO")

PYEOF

echo ""
echo -e "${GREEN}======================================================================"
echo "  Demo Complete!"
echo "======================================================================"
echo ""
echo "Architecture Used:"
echo "  Producer (Java) → Kafka Topic → Kafka Connect"
echo "                                     ↓"
echo "                         PGPEncryptionSMT Transform"
echo "                                     ↓"
echo "                         S3 Sink Connector"
echo "                                     ↓"
echo "                                  MinIO"
echo ""
echo "Access MinIO Console: http://localhost:9000"
echo "  Username: minioadmin"
echo "  Password: minioadmin"
echo ""
echo "Kafka Connect Status: http://localhost:8083"
echo ""
echo "View Connector Logs:"
echo "  docker logs banking-kafka-connect -f"
echo ""
