#!/bin/bash
#
# Banking Kafka Connect POC - Demo Script
#
# Usage:
#   ./scripts/demo.sh          # Full demo (start env + deploy + produce + show results)
#   ./scripts/demo.sh quick    # Quick demo (assumes env is running)
#   ./scripts/demo.sh stop     # Stop the environment
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
cd "$PROJECT_DIR"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

# Config
CONNECT_URL="http://localhost:8083"
MINIO_URL="http://localhost:9000"
CONNECTOR_NAME="banking-s3-sink"

print_header() {
    echo ""
    echo -e "${BLUE}══════════════════════════════════════════════════════════════${NC}"
    echo -e "${BLUE}  $1${NC}"
    echo -e "${BLUE}══════════════════════════════════════════════════════════════${NC}"
    echo ""
}

print_step() {
    echo -e "${CYAN}▶ $1${NC}"
}

print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

print_warning() {
    echo -e "${YELLOW}⚠ $1${NC}"
}

print_error() {
    echo -e "${RED}✗ $1${NC}"
}

wait_for_service() {
    local url=$1
    local name=$2
    local max_attempts=${3:-60}

    print_step "Waiting for $name..."
    for i in $(seq 1 $max_attempts); do
        if curl -s "$url" > /dev/null 2>&1; then
            print_success "$name is ready"
            return 0
        fi
        sleep 2
    done
    print_error "$name not available after $max_attempts attempts"
    return 1
}

generate_keys_if_needed() {
    if [ ! -f "$PROJECT_DIR/config/local/keys/pgp/bnpp-public.asc" ]; then
        print_step "Generating test cryptographic keys..."
        bash "$PROJECT_DIR/scripts/generate-test-keys.sh"
        print_success "Keys generated"
    else
        print_success "Cryptographic keys already exist"
    fi
}

start_environment() {
    print_header "Starting Environment"

    # Generate keys if needed (for PGP encryption demo)
    generate_keys_if_needed

    # Check if already running
    if curl -s "$CONNECT_URL" > /dev/null 2>&1; then
        print_success "Environment already running"
        return 0
    fi

    print_step "Starting Docker Compose..."
    docker compose -f docker/docker-compose.yml up -d

    # Wait for Kafka
    print_step "Waiting for Kafka..."
    sleep 10

    # Wait for Kafka Connect (can take a while due to plugin scanning)
    print_warning "Kafka Connect takes ~2-3 min to start (plugin scanning)..."
    wait_for_service "$CONNECT_URL" "Kafka Connect" 120

    # Create MinIO bucket if needed
    print_step "Ensuring MinIO bucket exists..."
    docker run --rm --network banking-network --entrypoint sh minio/mc -c \
        "mc alias set minio http://minio:9000 minioadmin minioadmin >/dev/null 2>&1; \
         mc mb --ignore-existing minio/banking-payments >/dev/null 2>&1" || true
    print_success "MinIO bucket ready"
}

stop_environment() {
    print_header "Stopping Environment"
    docker compose -f docker/docker-compose.yml down
    print_success "Environment stopped"
}

deploy_connector() {
    print_header "Deploying S3 Sink Connector"

    # Check if connector exists
    if curl -s "$CONNECT_URL/connectors/$CONNECTOR_NAME" | grep -q '"name"'; then
        print_step "Deleting existing connector..."
        curl -s -X DELETE "$CONNECT_URL/connectors/$CONNECTOR_NAME" > /dev/null
        sleep 2
    fi

    # Deploy with demo-friendly settings
    print_step "Deploying connector with demo settings..."

    curl -s -X POST -H "Content-Type: application/json" "$CONNECT_URL/connectors" -d '{
      "name": "'"$CONNECTOR_NAME"'",
      "config": {
        "connector.class": "io.confluent.connect.s3.S3SinkConnector",
        "tasks.max": "1",
        "topics": "payments-in",
        "s3.bucket.name": "banking-payments",
        "s3.region": "us-east-1",
        "flush.size": "10",
        "rotate.interval.ms": "10000",
        "partition.duration.ms": "3600000",
        "storage.class": "io.confluent.connect.s3.storage.S3Storage",
        "format.class": "com.banking.kafka.format.PGPEncryptedJsonFormat",
        "format.pgp.config.file": "/config/banks/bank-config.json",
        "format.pgp.institution.header": "X-Institution-Id",
        "format.pgp.event.type.header": "X-Event-Type",
        "format.pgp.event.version.header": "X-Event-Version",
        "partitioner.class": "com.banking.kafka.partitioner.FieldAndTimeBasedPartitioner",
        "partition.field.name": "headers.X-Institution-Id,headers.X-Event-Type,headers.X-Event-Version",
        "partition.field.format.path": "true",
        "path.format": "'"'"'year'"'"'=YYYY/'"'"'month'"'"'=MM/'"'"'day'"'"'=dd/'"'"'hour'"'"'=HH",
        "locale": "en-US",
        "timezone": "UTC",
        "timestamp.extractor": "Record",
        "key.converter": "org.apache.kafka.connect.storage.StringConverter",
        "value.converter": "org.apache.kafka.connect.json.JsonConverter",
        "value.converter.schemas.enable": "false",
        "transforms": "extractHeaders,transformPAN",
        "transforms.extractHeaders.type": "com.banking.kafka.transforms.HeadersToPayloadTransform",
        "transforms.extractHeaders.mandatory.headers": "X-Institution-Id,X-Event-Type,X-Event-Version,X-Event-Id",
        "transforms.extractHeaders.target.field": "headers",
        "transforms.extractHeaders.fail.on.missing.mandatory": "false",
        "transforms.extractHeaders.wrap.payload": "true",
        "transforms.extractHeaders.payload.field": "payload",
        "transforms.transformPAN.type": "com.banking.kafka.transforms.PANTransformationSMT",
        "transforms.transformPAN.source.field": "encryptedPrimaryAccountNumber",
        "transforms.transformPAN.default.strategy": "MASK",
        "transforms.transformPAN.rules": "BNK001:*:*:REMOVE,BNPP:*:*:DECRYPT,SOCGEN:*:*:REMOVE,*:*:*:MASK",
        "transforms.transformPAN.institution.id.header": "X-Institution-Id",
        "transforms.transformPAN.private.key.path": "/keys/my-institution/private-key.pem",
        "store.url": "http://minio:9000",
        "aws.access.key.id": "minioadmin",
        "aws.secret.access.key": "minioadmin",
        "errors.tolerance": "all",
        "errors.log.enable": "true"
      }
    }' > /dev/null

    sleep 3

    # Check status
    local status=$(curl -s "$CONNECT_URL/connectors/$CONNECTOR_NAME/status" | grep -o '"state":"[^"]*"' | head -1)
    if echo "$status" | grep -q "RUNNING"; then
        print_success "Connector deployed and running"
    else
        print_error "Connector deployment issue: $status"
        return 1
    fi
}

run_producer() {
    print_header "Running Demo Producer"

    # Build if needed
    if [ ! -f "target/kafka-connect-banking-poc-1.0.0-SNAPSHOT-uber.jar" ]; then
        print_step "Building project..."
        mvn package -DskipTests -q
    fi

    print_step "Sending messages (10 seconds)..."
    echo ""
    timeout 10 java -cp "target/kafka-connect-banking-poc-1.0.0-SNAPSHOT-uber.jar" \
        com.banking.kafka.demo.DemoProducer 2>&1 | grep -E "(Started|Sent|msg/sec)" | head -20 || true
    echo ""
    print_success "Messages sent"
}

show_results() {
    print_header "Results in MinIO"

    sleep 5  # Wait for files to be written

    print_step "Files created:"
    echo ""
    docker run --rm --network banking-network --entrypoint sh minio/mc -c \
        "mc alias set minio http://minio:9000 minioadmin minioadmin >/dev/null 2>&1; \
         mc find minio/banking-payments/ --name '*.json' 2>/dev/null" | head -20

    echo ""
    print_step "Sample file content:"
    echo ""

    # Get first file and show content
    local first_file=$(docker run --rm --network banking-network --entrypoint sh minio/mc -c \
        "mc alias set minio http://minio:9000 minioadmin minioadmin >/dev/null 2>&1; \
         mc find minio/banking-payments/ --name '*.json' 2>/dev/null" | head -1)

    if [ -n "$first_file" ]; then
        docker run --rm --network banking-network --entrypoint sh minio/mc -c \
            "mc alias set minio http://minio:9000 minioadmin minioadmin >/dev/null 2>&1; \
             mc cat '$first_file' 2>/dev/null" | python3 -m json.tool 2>/dev/null || echo "(no files yet)"
    fi

    echo ""
    print_header "Demo Complete"
    echo -e "  ${GREEN}MinIO Console:${NC} http://localhost:9001 (minioadmin/minioadmin)"
    echo -e "  ${GREEN}Kafka Connect:${NC} http://localhost:8083"
    echo ""
    echo -e "  ${YELLOW}Key features demonstrated:${NC}"
    echo "  - Hierarchical S3 partitioning (Institution/EventType/Version/Date)"
    echo "  - Multi-bank PAN transformation (REMOVE, DECRYPT, MASK per bank)"
    echo "  - PGP encryption: ONLY BNPP PAYMENT events are encrypted"
    echo "  - Kafka headers extracted to JSON payload"
    echo ""
}

# Main
case "${1:-full}" in
    full)
        start_environment
        deploy_connector
        run_producer
        show_results
        ;;
    quick)
        deploy_connector
        run_producer
        show_results
        ;;
    stop)
        stop_environment
        ;;
    *)
        echo "Usage: $0 [full|quick|stop]"
        echo ""
        echo "  full  - Start env, deploy connector, run demo (default)"
        echo "  quick - Deploy and run demo (env must be running)"
        echo "  stop  - Stop the environment"
        exit 1
        ;;
esac
