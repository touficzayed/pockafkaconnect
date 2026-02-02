# POC Kafka Connect - Plateforme Monétique Bancaire

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-11%2B-orange.svg)](https://openjdk.java.net/)
[![Kafka](https://img.shields.io/badge/Kafka-3.6%2B-black.svg)](https://kafka.apache.org/)
[![Tests](https://img.shields.io/badge/Tests-45%20passing-brightgreen.svg)](src/test/java/)

## Vue d'ensemble

POC d'un connecteur Kafka Connect personnalisé pour le traitement de messages monétiques bancaires, avec gestion avancée des PANs (Primary Account Numbers) chiffrés et export vers Object Storage (MinIO/IBM COS).

### 🎯 Fonctionnalités clés

- **🏦 Multi-banques**: Configuration différenciée par institution bancaire
- **🔐 Transformation du PAN**: 4 stratégies (REMOVE, DECRYPT, REKEY, NONE) pour gérer les numéros de carte chiffrés en JWE/RSA
- **🔒 Chiffrement PGP streaming**: Chiffrement à la volée sans buffering mémoire, configurable par banque
- **🎭 Multi-tenant**: Routage intelligent par institution bancaire via headers Kafka
- **📊 Partitioning déterministe**: Mapping CSV banque→partition ou Murmur2, 20 partitions/tasks pour scaling à 200+ banques
- **📝 Format JSONL**: Export streamable avec headers Kafka préservés
- **☁️ Cloud-ready**: Support MinIO (local) et IBM COS (cloud)

### ✅ Statut du Projet

- ✅ **Phase 1**: Setup environnement et structure projet
- ✅ **Phase 2**: SMT HeadersToPayload (extraction headers Kafka)
- ✅ **Phase 3**: SMT PANTransformation (REMOVE, DECRYPT, REKEY)
- ✅ **Phase 4**: Custom Partitioner (routage par institution)
- ✅ **Phase 5**: PGP Encryption (chiffrement par banque)
- ✅ **Phase 6**: Configuration multi-banques
- ✅ **Phase 7**: Partitioning déterministe (Murmur2 + CSV) et streaming PGP
- ⏳ **Phase 8**: Tests E2E et déploiement
- ⏳ **Phase 9**: Déploiement IBM Cloud

**45 tests unitaires** - 100% passants

## Architecture

Voir [docs/architecture.md](docs/architecture.md) pour la documentation complète.

```
Producer (Multi-Bank)
   ↓ (messages avec headers par banque)
Kafka Topic (payments-in)
   ↓ (partitionnement par institution)
Kafka Connect (20 tasks parallèles)
   ├─ HeadersToPayloadTransform → Extrait headers vers payload
   ├─ PANTransformationSMT → Transformation selon config banque
   └─ BankingHierarchicalPartitioner → Murmur2 / mapping CSV déterministe
   ↓
S3 Sink Connector
   ↓ (fichiers JSONL par banque, chiffrement PGP streaming intégré)
PGPOutputStreamWrapper (chiffrement à la volée, zéro buffering)
   ↓
MinIO/IBM COS
   └─ bnk001/, bnk002/, bnk003/, ...
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

# 3. Déployer le JAR custom
sudo cp target/kafka-connect-banking-poc-1.0.0-SNAPSHOT-uber.jar docker/connectors/banking-custom/
docker restart banking-kafka-connect

# 4. Créer le connecteur
curl -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d @config/connectors/s3-sink-connector.json

# 5. Tester - Toutes les banques
mvn exec:java \
  -Dexec.mainClass="com.banking.kafka.integration.MultiBankPaymentProducer" \
  -Dexec.args="localhost:9092 payments-in 10"

# Ou tester une seule banque
mvn exec:java \
  -Dexec.mainClass="com.banking.kafka.integration.MultiBankPaymentProducer" \
  -Dexec.args="localhost:9092 payments-in 20 BNK001"
```

Accès aux interfaces:
- MinIO Console: http://localhost:9001 (minioadmin/minioadmin)
- Kafka Connect REST API: http://localhost:8083
- Kafka UI (optionnel): http://localhost:8080

## Configuration Multi-Banques

### Scénarios supportés

| Banque | Stratégie PAN | PGP | Cas d'Usage |
|--------|---------------|-----|-------------|
| **BNK001** | REMOVE | ✅ ASCII | Conformité stricte - supprime le PAN |
| **BNK002** | DECRYPT | ❌ None | Système legacy - PAN en clair |
| **BNK003** | REKEY | ✅ Binary | Isolation - re-chiffre avec clé propre |
| **BNK004** | NONE | ✅ ASCII | Pas de PAN - utilise tokens uniquement |
| **BNK005** | DECRYPT | ✅ ASCII | Double chiffrement (PGP + S3) |

### Fichier de configuration

**Fichier**: `config/banks/bank-config.json`

```json
{
  "banks": {
    "BNK001": {
      "name": "Banque Nationale",
      "pan_strategy": "REMOVE",
      "pan_config": {
        "source_field": "encryptedPrimaryAccountNumber",
        "reason": "Conformité stricte PCI-DSS"
      },
      "pgp_encryption": {
        "enabled": true,
        "public_key_path": "/keys/pgp/bnk001-public.asc",
        "armor": true
      }
    },
    "BNK002": {
      "name": "Crédit Populaire",
      "pan_strategy": "DECRYPT",
      "pan_config": {
        "source_field": "encryptedPrimaryAccountNumber",
        "target_field": "primaryAccountNumber",
        "private_key_path": "/keys/bank-private-key.pem"
      },
      "pgp_encryption": {
        "enabled": false
      }
    }
  }
}
```

Voir [MULTI_BANK_SETUP.md](MULTI_BANK_SETUP.md) pour le guide complet.

## Structure du projet

```
kafka-connect-banking-poc/
├── docs/                          # Documentation technique
│   ├── architecture.md            # Design et architecture
│   ├── configuration.md           # Guide de configuration
│   └── next-steps.md              # Prochaines étapes
├── src/main/java/                 # Code source
│   └── com/banking/kafka/
│       ├── config/                # Configuration managers
│       │   └── BankConfigManager.java
│       ├── transforms/            # Single Message Transforms
│       │   ├── HeadersToPayloadTransform.java
│       │   └── PANTransformationSMT.java
│       ├── partitioner/           # Custom partitioner (Murmur2 + CSV mapping)
│       │   └── BankingHierarchicalPartitioner.java
│       └── crypto/                # JWE/PGP handlers
│           ├── JWEHandler.java
│           ├── PGPEncryptionHandler.java
│           ├── PGPOutputStreamWrapper.java  # Streaming PGP (zéro buffering)
│           ├── BankPGPEncryptor.java
│           └── FileKeyStorageProvider.java
├── src/test/java/                 # Tests (45 tests)
│   └── com/banking/kafka/
│       ├── transforms/            # Tests SMTs (10 tests)
│       ├── partitioner/           # Tests partitioner (18 tests)
│       ├── crypto/                # Tests crypto (17 tests)
│       └── integration/           # Producers de test
│           ├── BankingPaymentProducer.java
│           └── MultiBankPaymentProducer.java
├── config/                        # Configurations
│   ├── banks/                     # Config multi-banques
│   │   ├── bank-config.json
│   │   └── bank-partition-mapping.csv  # Mapping déterministe banque→partition
│   ├── connectors/                # Config connecteurs
│   │   ├── s3-sink-connector.json
│   │   └── s3-sink-connector-multibank.json  # 20 tasks + mapping CSV
│   └── local/                     # Config environnement local
│       └── keys/                  # Clés de chiffrement (gitignored)
├── docker/                        # Infrastructure locale
│   ├── docker-compose.yml
│   └── connectors/                # JAR custom connector
├── DEPLOYMENT.md                  # Guide de déploiement
├── MULTI_BANK_SETUP.md            # Guide multi-banques
└── QUICKSTART.md                  # Démarrage rapide
```

## Modes de transformation du PAN

### 1. REMOVE (BNK001)
Supprime complètement le champ `encryptedPrimaryAccountNumber`

```properties
transforms.panTransform.strategy=REMOVE
transforms.panTransform.source.field=encryptedPrimaryAccountNumber
```

**Cas d'usage**: Conformité PCI-DSS stricte

### 2. DECRYPT (BNK002, BNK005)
Déchiffre le JWE et expose le PAN en clair

```properties
transforms.panTransform.strategy=DECRYPT
transforms.panTransform.source.field=encryptedPrimaryAccountNumber
transforms.panTransform.target.field=primaryAccountNumber
transforms.panTransform.private.key.path=/keys/bank-private-key.pem
```

**Cas d'usage**: Système legacy, tokenisation

### 3. REKEY (BNK003)
Transchiffre avec la clé publique de la banque partenaire

```properties
transforms.panTransform.strategy=REKEY
transforms.panTransform.private.key.path=/keys/bank-private-key.pem
transforms.panTransform.partner.keys.mapping.path=/config/partner-keys.json
transforms.panTransform.institution.id.header=X-Institution-Id
```

**Cas d'usage**: Isolation multi-tenant, partage sécurisé

### 4. NONE (BNK004)
Aucune transformation du PAN (pas de champ PAN dans le message)

```properties
# Pas de transformation PAN configurée
```

**Cas d'usage**: Messages sans PAN, utilisation de tokens uniquement

## Chiffrement PGP par Banque

Configuration dans `config/banks/bank-config.json`:

```json
{
  "banks": {
    "BNK001": {
      "pgp_encryption": {
        "enabled": true,
        "public_key_path": "/keys/pgp/bnk001-public.asc",
        "armor": true  // ASCII armor pour lisibilité
      }
    },
    "BNK003": {
      "pgp_encryption": {
        "enabled": true,
        "public_key_path": "/keys/pgp/bnk003-public.asc",
        "armor": false  // Binaire pour compacité
      }
    }
  }
}
```

## Format des messages

### Message Kafka (input)

```json
{
  "transactionId": "TXN-001",
  "amount": 150.00,
  "currency": "EUR",
  "encryptedPrimaryAccountNumber": "eyJhbGciOiJSU0EtT0FFUC0yNTYi...",
  "merchantId": "MERCHANT-001",
  "timestamp": "2026-02-02T14:30:00Z"
}
```

Headers Kafka:
- `X-Institution-Id: BNK001`
- `X-Event-Type: PAYMENT`
- `X-Version: 1.0`

### Fichier JSONL (output pour BNK001 - REMOVE)

```jsonl
{"transactionId":"TXN-001","amount":150.00,"currency":"EUR","merchantId":"MERCHANT-001","timestamp":"2026-02-02T14:30:00Z","metadata":{"X-Institution-Id":"BNK001","X-Event-Type":"PAYMENT","X-Version":"1.0"}}
```

**Note**: Champ `encryptedPrimaryAccountNumber` supprimé

### Fichier JSONL (output pour BNK002 - DECRYPT)

```jsonl
{"transactionId":"TXN-002","amount":250.00,"currency":"EUR","primaryAccountNumber":"4532123456789012","merchantId":"MERCHANT-002","timestamp":"2026-02-02T14:30:00Z","metadata":{"X-Institution-Id":"BNK002","X-Event-Type":"PAYMENT"}}
```

**Note**: PAN déchiffré en clair

Chemin dans MinIO/COS:
```
bnk001/year=2026/month=02/day=02/hour=14/payments-in+0+0000000000.json
```

## Sécurité

- **PCI-DSS**: Le PAN en clair n'existe jamais sur disque, seulement en mémoire
- **Clés privées**: Stockées hors du repository (`.gitignore`)
- **Multi-tenant**: Isolation par banque avec re-chiffrement
- **PGP streaming**: Chiffrement à la volée via `PGPOutputStreamWrapper` — zéro buffering mémoire, scalable à 200+ banques
- **Partitioning déterministe**: Mapping CSV explicite banque→partition pour distribution prévisible de la charge
- **Production**: Utilisation d'IBM Key Protect pour la gestion des clés
- **Transport**: TLS activé sur Kafka et COS en production

## Tests

```bash
# Tests unitaires (45 tests)
mvn test

# Tests d'intégration
mvn verify -P integration-tests

# Test producer - Toutes les banques
mvn exec:java \
  -Dexec.mainClass="com.banking.kafka.integration.MultiBankPaymentProducer" \
  -Dexec.args="localhost:9092 payments-in 10"

# Test producer - Une seule banque
mvn exec:java \
  -Dexec.mainClass="com.banking.kafka.integration.MultiBankPaymentProducer" \
  -Dexec.args="localhost:9092 payments-in 20 BNK001"
```

## Vérification des Résultats

```bash
# Lister les fichiers par banque
docker exec banking-minio-init mc find minio/banking-payments/bnk001 --name "*.json"

# Télécharger un fichier
docker exec banking-minio-init mc cp \
  minio/banking-payments/bnk001/.../file.json \
  /tmp/output.json

# Voir le contenu (si non chiffré PGP)
cat /tmp/output.json | jq .

# Déchiffrer un fichier PGP
gpg --decrypt /tmp/output.json > /tmp/decrypted.json
```

## Monitoring

Métriques exposées:
- `banking.pan.removed.total`: Nombre de PANs supprimés (BNK001)
- `banking.pan.decrypted.total`: Nombre de PANs déchiffrés (BNK002, BNK005)
- `banking.pan.rekeyed.total`: Nombre de PANs transchiffrés (BNK003)
- `banking.files.written.total`: Nombre de fichiers écrits par institution
- `banking.pgp.encrypted.total`: Nombre de fichiers chiffrés PGP

Accès aux métriques: http://localhost:8083/metrics

## Documentation

- 📖 [DEPLOYMENT.md](DEPLOYMENT.md) - Guide de déploiement complet
- 🏦 [MULTI_BANK_SETUP.md](MULTI_BANK_SETUP.md) - Configuration multi-banques
- 🚀 [QUICKSTART.md](QUICKSTART.md) - Démarrage rapide
- 🏗️ [docs/architecture.md](docs/architecture.md) - Architecture détaillée
- ⚙️ [docs/configuration.md](docs/configuration.md) - Configuration avancée
- 🔜 [docs/next-steps.md](docs/next-steps.md) - Prochaines étapes

## Déploiement cloud (IBM)

```bash
# Build de l'image Docker
docker build -t banking-kafka-connect:latest .

# Push vers IBM Container Registry
ibmcloud cr build -t icr.io/namespace/banking-kafka-connect:latest .

# Déploiement sur IKS
kubectl apply -f k8s/
```

Voir [DEPLOYMENT.md](DEPLOYMENT.md) pour le guide complet.

## Contribution

Ce projet est un POC interne. Pour toute question, contacter l'équipe architecture.

## Licence

Apache License 2.0

## Références

- [Architecture complète](docs/architecture.md)
- [Configuration multi-banques](MULTI_BANK_SETUP.md)
- [Confluent S3 Sink Connector](https://docs.confluent.io/kafka-connect-s3-sink/current/)
- [IBM Key Protect](https://cloud.ibm.com/docs/key-protect)
- [Nimbus JOSE+JWT](https://connect2id.com/products/nimbus-jose-jwt)
- [Bouncy Castle PGP](https://www.bouncycastle.org/java.html)
