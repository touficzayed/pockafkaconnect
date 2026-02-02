#!/bin/bash
# ====================================================================
# Start Local Banking POC Environment
# ====================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
DOCKER_DIR="$PROJECT_ROOT/docker"

echo "=========================================="
echo "Banking POC - Starting Local Environment"
echo "=========================================="
echo ""

# Check if Docker is running
if ! docker info > /dev/null 2>&1; then
    echo "❌ Error: Docker is not running"
    echo "   Please start Docker and try again"
    exit 1
fi

# Check if keys exist
if [ ! -f "$PROJECT_ROOT/config/local/keys/my-institution/private-key.pem" ]; then
    echo "⚠️  Test keys not found. Generating..."
    bash "$SCRIPT_DIR/generate-test-keys.sh"
    echo ""
fi

# Navigate to docker directory
cd "$DOCKER_DIR"

# Parse command line arguments
WITH_UI=""
if [ "$1" = "--with-ui" ]; then
    WITH_UI="--profile with-ui"
    echo "ℹ️  Starting with Kafka UI (http://localhost:8080)"
fi

# Stop any existing containers
echo "Stopping any existing containers..."
docker-compose down -v 2>/dev/null || true
echo ""

# Start services
echo "Starting services..."
echo "  • Zookeeper"
echo "  • Kafka"
echo "  • Kafka Connect"
echo "  • MinIO (S3-compatible)"
if [ -n "$WITH_UI" ]; then
    echo "  • Kafka UI"
fi
echo ""

docker-compose up -d $WITH_UI

echo ""
echo "Waiting for services to be ready..."
echo ""

# Wait for Kafka Connect to be ready
echo -n "Waiting for Kafka Connect"
for i in {1..30}; do
    if curl -s http://localhost:8083/ > /dev/null 2>&1; then
        echo " ✓"
        break
    fi
    echo -n "."
    sleep 2
done

echo ""
echo "=========================================="
echo "✓ Environment Started Successfully!"
echo "=========================================="
echo ""
echo "Services available at:"
echo "  • Kafka:           localhost:9092"
echo "  • Kafka Connect:   http://localhost:8083"
echo "  • MinIO Console:   http://localhost:9001"
echo "                     (user: minioadmin, password: minioadmin)"
if [ -n "$WITH_UI" ]; then
    echo "  • Kafka UI:        http://localhost:8080"
fi
echo ""
echo "MinIO buckets created:"
echo "  • banking-payments (output data)"
echo "  • banking-config (configuration)"
echo ""
echo "Next steps:"
echo "  1. Deploy the connector:"
echo "     ./scripts/deploy-connector.sh"
echo ""
echo "  2. Send test messages:"
echo "     ./scripts/test-producer.sh"
echo ""
echo "  3. View logs:"
echo "     docker-compose -f $DOCKER_DIR/docker-compose.yml logs -f kafka-connect"
echo ""
echo "To stop the environment:"
echo "  docker-compose -f $DOCKER_DIR/docker-compose.yml down"
echo ""
