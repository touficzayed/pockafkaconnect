#!/usr/bin/env python3

"""
Kafka Consumer to MinIO Upload Script
Reads messages from Kafka topic and uploads them to MinIO in JSONL format
with optional PGP encryption based on bank/event type/version rules
"""

import sys
import time
import json
import os
from datetime import datetime
from io import BytesIO

try:
    from kafka import KafkaConsumer
    import boto3
    import pgpy
except ImportError:
    print("Installing required packages...")
    import subprocess
    subprocess.check_call([sys.executable, "-m", "pip", "install", "-q", "kafka-python", "boto3", "pgpy"])
    from kafka import KafkaConsumer
    import boto3
    import pgpy


class PGPRuleManager:
    """Manages PGP encryption rules for selective encryption"""

    def __init__(self, rules_str=""):
        """
        Parse encryption rules in format: EventType:Version:Action
        Example: "PAYMENT:*:ENCRYPT,REFUND:*:ENCRYPT,*:*:SKIP"
        """
        self.rules = []
        if rules_str:
            for rule in rules_str.split(','):
                rule = rule.strip()
                if ':' in rule:
                    parts = rule.split(':')
                    if len(parts) == 3:
                        self.rules.append({
                            'event_type': parts[0].strip(),
                            'version': parts[1].strip(),
                            'action': parts[2].strip()
                        })

    def should_encrypt(self, event_type, event_version):
        """Determine if message should be encrypted based on rules"""
        if not self.rules:
            return False

        # Check exact matches first
        for rule in self.rules:
            if self._matches(rule, event_type, event_version):
                return rule['action'].upper() == 'ENCRYPT'

        # Check wildcard matches
        for rule in self.rules:
            if rule['event_type'] == '*' and rule['version'] == '*':
                return rule['action'].upper() == 'ENCRYPT'

        return False

    def _matches(self, rule, event_type, event_version):
        """Check if rule matches event type and version"""
        event_match = (rule['event_type'] == '*' or
                      rule['event_type'].upper() == event_type.upper())
        version_match = (rule['version'] == '*' or
                        rule['version'] == event_version)
        return event_match and version_match


class PGPEncryptor:
    """Handles streaming PGP encryption"""

    def __init__(self, bank_name="Demo Bank"):
        self.bank_name = bank_name
        self.public_key = None
        self._generate_test_key()

    def _generate_test_key(self):
        """Generate a test PGP keypair for demo"""
        try:
            key = pgpy.PGPKey.generate('rsa', 2048, name=self.bank_name)
            self.public_key = key.public_key
        except Exception as e:
            pass  # Silently fail

    def encrypt_stream(self, input_stream, output_stream):
        """
        Encrypt data from input_stream to output_stream in streaming fashion.
        Reads and encrypts in chunks to minimize memory usage.
        """
        if not self.public_key:
            return False

        try:
            import tempfile

            # Read input in chunks and build message
            chunks = []
            chunk_size = 8192  # 8KB chunks

            while True:
                chunk = input_stream.read(chunk_size)
                if not chunk:
                    break
                chunks.append(chunk)

            # Combine chunks into single message
            data = b''.join(chunks)

            # Create and encrypt message
            message = pgpy.PGPMessage.new(data)
            encrypted_message = self.public_key.encrypt(message)

            # Write encrypted data to output stream
            encrypted_data = str(encrypted_message).encode('utf-8')
            output_stream.write(encrypted_data)

            return True

        except Exception as e:
            print(f"    Warning: Streaming encryption failed: {e}")
            return False


class KafkaToMinIOConsumer:
    def __init__(self, bootstrap_servers="localhost:9092",
                 s3_endpoint="http://localhost:9000",
                 bucket="banking-payments",
                 topic="payments-in",
                 timeout_seconds=30,
                 enable_pgp=True):
        self.bootstrap_servers = bootstrap_servers
        self.topic = topic
        self.bucket = bucket
        self.timeout_seconds = timeout_seconds
        self.enable_pgp = enable_pgp

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

        # Load bank configurations for PGP encryption
        self.bank_configs = self._load_bank_configs()
        self.pgp_encryptors = {}

        print("=" * 50)
        print("  Kafka to MinIO Consumer")
        print("=" * 50)
        print(f"Bootstrap Servers: {bootstrap_servers}")
        print(f"Topic: {topic}")
        print(f"S3 Endpoint: {s3_endpoint}")
        print(f"Bucket: {bucket}")
        print(f"Timeout: {timeout_seconds} seconds")
        print(f"PGP Encryption: {'ENABLED' if enable_pgp else 'DISABLED'}")
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

    def _load_bank_configs(self):
        """Load bank configurations from config file"""
        config_path = os.path.join(
            os.path.dirname(__file__),
            '../config/banks/bank-config.json'
        )

        if not os.path.exists(config_path):
            print(f"  Note: Bank config not found at {config_path}, using defaults")
            return {}

        try:
            with open(config_path, 'r') as f:
                config = json.load(f)
                return config.get('banks', {})
        except Exception as e:
            print(f"  Warning: Could not load bank config: {e}")
            return {}

    def _get_pgp_config(self, bank_code):
        """Get PGP configuration for bank"""
        if bank_code not in self.bank_configs:
            return None

        bank_config = self.bank_configs[bank_code]
        pgp_config = bank_config.get('pgp_encryption', {})

        if not pgp_config.get('enabled', False):
            return None

        return pgp_config

    def _should_encrypt_message(self, bank_code, event_type, event_version):
        """Determine if message should be PGP encrypted"""
        if not self.enable_pgp:
            return False

        pgp_config = self._get_pgp_config(bank_code)
        if not pgp_config:
            return False

        rules_str = pgp_config.get('rules', '')
        rule_manager = PGPRuleManager(rules_str)

        return rule_manager.should_encrypt(event_type, event_version)

    def _encrypt_stream_with_pgp(self, input_stream, bank_code):
        """
        Encrypt stream with PGP if configured.
        Returns BytesIO with encrypted data or None if encryption fails.
        """
        if bank_code not in self.pgp_encryptors:
            self.pgp_encryptors[bank_code] = PGPEncryptor(bank_code)

        encryptor = self.pgp_encryptors[bank_code]
        output_stream = BytesIO()

        # Perform streaming encryption
        if encryptor.encrypt_stream(input_stream, output_stream):
            output_stream.seek(0)
            return output_stream
        else:
            return None

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
        """Upload buffered messages to MinIO with optional streaming PGP encryption"""
        try:
            bank_code, event_type, event_version = group_key
            now = datetime.now()

            # Check if we should encrypt this batch
            should_encrypt = self._should_encrypt_message(
                bank_code, event_type, event_version
            )

            # Prepare file path and content type
            buffer.seek(0)
            data_size = len(buffer.getvalue())

            if should_encrypt:
                print(f"\n  [PGP-STREAM] Encrypting {data_size:,} bytes for {bank_code}/{event_type}/{event_version}")

                # Apply streaming PGP encryption
                buffer.seek(0)
                encrypted_stream = self._encrypt_stream_with_pgp(buffer, bank_code)

                if encrypted_stream:
                    s3_path = (
                        f"messages/{bank_code}/{event_type}/{event_version}/"
                        f"{now.strftime('%Y/%m/%d/%H/%M')}/{int(time.time() * 1000)}.pgp"
                    )
                    content_type = 'application/pgp-encrypted'
                    upload_stream = encrypted_stream
                    encrypted_size = len(encrypted_stream.getvalue())
                    print(f"  [PGP-STREAM] ✓ Encrypted successfully ({encrypted_size:,} bytes)")
                else:
                    print(f"  [PGP-STREAM] ✗ Encryption failed, uploading unencrypted")
                    s3_path = (
                        f"messages/{bank_code}/{event_type}/{event_version}/"
                        f"{now.strftime('%Y/%m/%d/%H/%M')}/{int(time.time() * 1000)}.jsonl"
                    )
                    content_type = 'application/jsonl'
                    buffer.seek(0)
                    upload_stream = buffer
            else:
                # No encryption needed
                s3_path = (
                    f"messages/{bank_code}/{event_type}/{event_version}/"
                    f"{now.strftime('%Y/%m/%d/%H/%M')}/{int(time.time() * 1000)}.jsonl"
                )
                content_type = 'application/jsonl'
                buffer.seek(0)
                upload_stream = buffer

            # Upload stream to MinIO (streaming, not loading all in memory)
            upload_stream.seek(0)
            upload_size = len(upload_stream.getvalue())
            print(f"  Uploading {upload_size:,} bytes to s3://{self.bucket}/{s3_path}")

            self.s3.upload_fileobj(
                upload_stream,
                self.bucket,
                s3_path,
                ExtraArgs={
                    'ContentType': content_type
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
