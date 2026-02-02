# POC Kafka Connect - Plateforme Monétique Bancaire

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-11%2B-orange.svg)](https://openjdk.java.net/)
[![Kafka](https://img.shields.io/badge/Kafka-3.6%2B-black.svg)](https://kafka.apache.org/)

## Vue d'ensemble

POC d'un connecteur Kafka Connect personnalisé pour le traitement de messages monétiques bancaires, avec gestion avancée des PANs (Primary Account Numbers) chiffrés et export vers Object Storage (MinIO/IBM COS).

### Fonctionnalités clés

- **Transformation du PAN**: 3 modes (REMOVE, DECRYPT, REKEY) pour gérer les numéros de carte chiffrés en JWE/RSA
- **Multi-tenant**: Routage intelligent par institution bancaire via headers Kafka
- **Partitioning hiérarchique**: Organisation par institution/type-event/version/date-heure
- **Format JSONL**: Export streamable avec headers Kafka préservés
- **PGP optionnel**: Chiffrement des fichiers en streaming
- **Cloud-ready**: Support MinIO (local) et IBM COS (cloud)

## Architecture

Voir [docs/architecture.md](docs/architecture.md) pour la documentation complète.

```
Kafka Topic → Custom S3 Sink Connector → MinIO/IBM COS
              (SMTs + Partitioner)        (JSONL files)
```

## Démarrage rapide

### Prérequis

- Java 11+
- Docker & Docker Compose
- Maven 3.6+

### Environnement local

```bash
# 1. Démarrer l'infrastructure (Kafka + MinIO)
cd docker
docker-compose up -d

# 2. Builder le connector
mvn clean package

# 3. Déployer le connector
./scripts/deploy-local.sh

# 4. Tester
./scripts/test-producer.sh
```

Accès aux interfaces:
- MinIO Console: http://localhost:9001 (admin/password123)
- Kafka Connect REST API: http://localhost:8083

## Structure du projet

```
kafka-connect-banking-poc/
├── docs/                      # Documentation technique
│   ├── architecture.md        # Design et architecture
│   ├── configuration.md       # Guide de configuration
│   └── deployment.md          # Guide de déploiement
├── src/main/java/             # Code source
│   └── com/banking/kafka/
│       ├── transforms/        # Single Message Transforms
│       ├── partitioner/       # Custom partitioner
│       └── crypto/            # JWE/PGP handlers
├── config/                    # Configurations
│   ├── local/                 # Config environnement local
│   │   ├── connector.properties
│   │   └── keys/              # Clés de chiffrement (gitignored)
│   └── cloud/                 # Config IBM Cloud
├── docker/                    # Infrastructure locale
│   ├── docker-compose.yml
│   └── Dockerfile.connect
├── tests/                     # Tests
│   ├── unit/
│   └── integration/
└── scripts/                   # Automation scripts
    ├── deploy-local.sh
    └── test-producer.sh
```

## Configuration

### Exemple minimal

```properties
# connector.properties
name=banking-s3-sink
connector.class=io.confluent.connect.s3.S3SinkConnector
topics=payments-in

# Transformation du PAN
transforms.panTransform.strategy=DECRYPT
transforms.panTransform.private.key.path=/keys/private-key.pem

# Headers Kafka à préserver
transforms.headersToPayload.mandatory.headers=X-Institution-Id,X-Event-Type,X-Event-Version
transforms.headersToPayload.optional.headers=X-Original-Request-Id,X-User-Id

# Partitioning
partitioner.class=com.banking.kafka.partitioner.BankingHierarchicalPartitioner
```

Voir [docs/configuration.md](docs/configuration.md) pour tous les paramètres.

## Format des messages

### Message Kafka (input)

```json
{
  "transactionId": "txn-123",
  "amount": 150.00,
  "currency": "EUR",
  "encryptedPrimaryAccountNumber": "eyJhbGciOiJSU0EtT0FFUC0yNTYi...",
  "merchantId": "merchant-001",
  "timestamp": "2026-02-02T14:30:00Z"
}
```

Headers Kafka:
- `X-Institution-Id: BNK001`
- `X-Event-Type: PAYMENT_AUTHORIZED`
- `X-Event-Version: v1`

### Fichier JSONL (output)

```jsonl
{"headers":{"X-Institution-Id":"BNK001","X-Event-Type":"PAYMENT_AUTHORIZED","X-Event-Version":"v1"},"payload":{"transactionId":"txn-123","amount":150.00,"currency":"EUR","primaryAccountNumber":"1234567890123456","merchantId":"merchant-001","timestamp":"2026-02-02T14:30:00Z"}}
```

Chemin dans COS:
```
BNK001/PAYMENT_AUTHORIZED/v1/year=2026/month=02/day=02/hour=14/payments-000001.jsonl
```

## Modes de transformation du PAN

### 1. REMOVE
Supprime complètement le champ `encryptedPrimaryAccountNumber`

```properties
transforms.panTransform.strategy=REMOVE
```

### 2. DECRYPT
Déchiffre le JWE et expose le PAN en clair

```properties
transforms.panTransform.strategy=DECRYPT
transforms.panTransform.private.key.path=/keys/my-private-key.pem
```

### 3. REKEY
Transchiffre avec la clé publique de la banque partenaire

```properties
transforms.panTransform.strategy=REKEY
transforms.panTransform.private.key.path=/keys/my-private-key.pem
transforms.panTransform.partner.keys.mapping.path=/config/partner-keys.json
```

Format du fichier `partner-keys.json`:
```json
{
  "BNK001": "/keys/partners/bnk001-public.pem",
  "BNK002": "/keys/partners/bnk002-public.pem"
}
```

## Sécurité

- **PCI-DSS**: Le PAN en clair n'existe jamais sur disque, seulement en mémoire
- **Clés privées**: Stockées hors du repository (`.gitignore`)
- **Production**: Utilisation d'IBM Key Protect pour la gestion des clés
- **Transport**: TLS activé sur Kafka et COS en production

## Tests

```bash
# Tests unitaires
mvn test

# Tests d'intégration
mvn verify -P integration-tests

# Test E2E local
./scripts/run-e2e-test.sh
```

## Déploiement cloud (IBM)

Voir [docs/deployment.md](docs/deployment.md) pour le guide complet.

```bash
# Build de l'image Docker
docker build -t banking-kafka-connect:latest .

# Push vers IBM Container Registry
ibmcloud cr build -t icr.io/namespace/banking-kafka-connect:latest .

# Déploiement sur IKS
kubectl apply -f k8s/
```

## Monitoring

Métriques exposées:
- `banking.pan.decrypted.total`: Nombre de PANs déchiffrés
- `banking.pan.removed.total`: Nombre de PANs supprimés
- `banking.pan.rekeyed.total`: Nombre de PANs transchiffrés
- `banking.files.written.total`: Nombre de fichiers écrits par institution

Accès aux métriques: http://localhost:8083/metrics

## Roadmap

- [ ] Phase 1: Setup environnement et structure projet ✅
- [ ] Phase 2: SMT HeadersToPayload
- [ ] Phase 3: SMT PANTransformation (REMOVE, DECRYPT, REKEY)
- [ ] Phase 4: Custom Partitioner
- [ ] Phase 5: PGP Encryption
- [ ] Phase 6: Tests E2E
- [ ] Phase 7: Déploiement IBM Cloud

## Contribution

Ce projet est un POC interne. Pour toute question, contacter l'équipe architecture.

## Licence

Apache License 2.0

## Références

- [Architecture complète](docs/architecture.md)
- [Guide de configuration](docs/configuration.md)
- [Confluent S3 Sink Connector](https://docs.confluent.io/kafka-connect-s3-sink/current/)
- [IBM Key Protect](https://cloud.ibm.com/docs/key-protect)
