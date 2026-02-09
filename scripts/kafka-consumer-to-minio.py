#!/usr/bin/env python3

"""
Kafka Consumer to MinIO Upload Script
Reads messages from Kafka topic and uploads them to MinIO in JSONL format
"""

import sys
import time
import json
from datetime import datetime
from io import BytesIO

try:
    from kafka import KafkaConsumer
    import boto3
except ImportError:
    print("Installing required packages...")
    import subprocess
    subprocess.check_call([sys.executable, "-m", "pip", "install", "-q", "kafka-python", "boto3"])
    from kafka import KafkaConsumer
    import boto3


class KafkaToMinIOConsumer:
    def __init__(self, bootstrap_servers="localhost:9092",
                 s3_endpoint="http://localhost:9000",
                 bucket="banking-payments",
                 topic="payments-in",
                 timeout_seconds=30):
        self.bootstrap_servers = bootstrap_servers
        self.topic = topic
        self.bucket = bucket
        self.timeout_seconds = timeout_seconds

        # Initialize Kafka consumer
        self.consumer = KafkaConsumer(
            topic,
            bootstrap_servers=[bootstrap_servers],
            group_id=f"consumer-{int(time.time())}",
            auto_offset_reset='earliest',
            consumer_timeout_ms=1000,
            value_deserializer=lambda m: m.decode('utf-8') if m else None
        )

        # Initialize S3 client for MinIO
        self.s3 = boto3.client(
            's3',
            endpoint_url=s3_endpoint,
            aws_access_key_id='minioadmin',
            aws_secret_access_key='minioadmin',
            region_name='us-east-1'
        )

        print("=" * 50)
        print("  Kafka to MinIO Consumer")
        print("=" * 50)
        print(f"Bootstrap Servers: {bootstrap_servers}")
        print(f"Topic: {topic}")
        print(f"S3 Endpoint: {s3_endpoint}")
        print(f"Bucket: {bucket}")
        print(f"Timeout: {timeout_seconds} seconds")
        print()

    def consume_and_upload(self):
        """Consume messages from Kafka and upload to MinIO grouped by bank/event"""
        # Use a dictionary to group messages by (bank_code, event_type, event_version)
        buffers = {}
        message_count = 0
        start_time = time.time()

        print(f"Consuming messages from Kafka for {self.timeout_seconds} seconds...\n")

        while time.time() - start_time < self.timeout_seconds:
            try:
                for message in self.consumer:
                    if message.value:
                        # Extract metadata from Kafka headers
                        bank_code = self._get_header(message, 'X-Institution-Id', 'UNKNOWN')
                        event_type = self._get_header(message, 'X-Event-Type', 'UNKNOWN')
                        event_version = self._get_header(message, 'X-Event-Version', 'v1.0')

                        # Create key for grouping
                        group_key = (bank_code, event_type, event_version)

                        # Initialize buffer for this group if not exists
                        if group_key not in buffers:
                            buffers[group_key] = {'buffer': BytesIO(), 'count': 0}

                        # Create record with headers and payload
                        record = self._create_record(message)
                        record_json = json.dumps(record, separators=(',', ':'))

                        # Add message to appropriate buffer
                        buffers[group_key]['buffer'].write((record_json + "\n").encode('utf-8'))
                        buffers[group_key]['count'] += 1
                        message_count += 1

                        if message_count % 100 == 0:
                            print(".", end="", flush=True)

                        # Upload when buffer reaches 500 messages
                        if buffers[group_key]['count'] >= 500:
                            self._upload_buffer(buffers[group_key]['buffer'], group_key)
                            buffers[group_key] = {'buffer': BytesIO(), 'count': 0}

                        # Check timeout
                        if time.time() - start_time >= self.timeout_seconds:
                            break

            except Exception as e:
                print(f"\nWarning: {e}")
                break

        # Final upload for all remaining buffers
        for group_key, data in buffers.items():
            if data['buffer'].tell() > 0:
                self._upload_buffer(data['buffer'], group_key)

        print(f"\n\n✓ Total messages consumed: {message_count}")
        return message_count

    def _create_record(self, message):
        """Create record with headers and payload structure"""
        # Extract all headers
        headers = {}
        if message.headers:
            for header_key, header_value in message.headers:
                if isinstance(header_value, bytes):
                    headers[header_key] = header_value.decode('utf-8')
                else:
                    headers[header_key] = str(header_value)

        # Parse payload as JSON
        try:
            payload = json.loads(message.value)
        except json.JSONDecodeError:
            payload = {"raw": message.value}

        return {
            "headers": headers,
            "payload": payload
        }

    def _get_header(self, message, header_name, default='UNKNOWN'):
        """Extract header value from Kafka message"""
        if message.headers:
            for header_key, header_value in message.headers:
                if header_key == header_name:
                    if isinstance(header_value, bytes):
                        return header_value.decode('utf-8')
                    return str(header_value)
        return default

    def _upload_buffer(self, buffer, group_key):
        """Upload buffered messages to MinIO with path based on bank/event/version"""
        try:
            buffer.seek(0)
            data = buffer.read()

            if not data:
                return

            bank_code, event_type, event_version = group_key
            now = datetime.now()

            # Structure: messages/{bank_code}/{event_type}/{event_version}/YYYY/MM/DD/HH/mm/timestamp.jsonl
            s3_path = (f"messages/{bank_code}/{event_type}/{event_version}/"
                      f"{now.strftime('%Y/%m/%d/%H/%M')}/{int(time.time() * 1000)}.jsonl")

            print(f"\n  Uploading {len(data):,} bytes to s3://{self.bucket}/{s3_path}")

            self.s3.upload_fileobj(
                BytesIO(data),
                self.bucket,
                s3_path,
                ExtraArgs={
                    'ContentType': 'application/jsonl'
                }
            )

            print(f"  ✓ Uploaded {s3_path}")

        except Exception as e:
            print(f"\n  ✗ Upload failed: {e}")
            import traceback
            traceback.print_exc()

    def close(self):
        """Close consumer"""
        self.consumer.close()


if __name__ == "__main__":
    consumer = None
    try:
        # Parse arguments
        bootstrap = sys.argv[1] if len(sys.argv) > 1 else "localhost:9092"
        endpoint = sys.argv[2] if len(sys.argv) > 2 else "http://localhost:9000"

        consumer = KafkaToMinIOConsumer(
            bootstrap_servers=bootstrap,
            s3_endpoint=endpoint,
            timeout_seconds=30
        )

        count = consumer.consume_and_upload()

        if count == 0:
            print("\nℹ No messages found in topic")
            sys.exit(1)

    except Exception as e:
        print(f"✗ Error: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)
    finally:
        if consumer:
            consumer.close()
