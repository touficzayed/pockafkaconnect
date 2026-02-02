# Architecture POC Kafka Connect - Monétique Bancaire

## 1. Vue d'ensemble

### 1.1. Objectif du POC

Ce POC vise à démontrer la faisabilité d'une solution Kafka Connect pour :
- **Lire** des messages monétiques depuis un topic Kafka
- **Transformer** les données (gestion du PAN chiffré en JWE)
- **Router** par institution bancaire (multi-tenant)
- **Écrire** dans un Object Storage (MinIO local / IBM COS cloud) au format JSONL

### 1.2. Architecture High-Level

```
┌─────────────────┐
│  Kafka Topic    │  Messages monétiques avec PAN chiffré (JWE/RSA)
│  payments-in    │  Headers: X-Institution-Id, X-Event-Type, etc.
└────────┬────────┘
         │
         ▼
┌──────────────────────────────────────────────────────────────────┐
│           Kafka Connect Worker(s)                                │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │  Custom Banking S3 Sink Connector                          │  │
│  │  (extends Confluent S3 Sink)                               │  │
│  │                                                             │  │
│  │  ┌──────────────────────────────────────────────────────┐  │  │
│  │  │  Bank Configuration Manager                          │  │  │
│  │  │  • Load config from /config/banks/bank-config.json  │  │  │
│  │  │  • Per-bank PAN strategy (REMOVE/DECRYPT/REKEY)     │  │  │
│  │  │  • Per-bank PGP encryption settings                 │  │  │
│  │  └──────────────────────────────────────────────────────┘  │  │
│  │                                                             │  │
│  │  ┌──────────────────────────────────────────────────────┐  │  │
│  │  │  SMT Chain (Single Message Transforms)               │  │  │
│  │  │  1. Extract Headers → headers field                  │  │  │
│  │  │  2. PAN Transformation (bank-specific strategy)      │  │  │
│  │  │  3. JSONL Formatting                                 │  │  │
│  │  └──────────────────────────────────────────────────────┘  │  │
│  │                                                             │  │
│  │  ┌──────────────────────────────────────────────────────┐  │  │
│  │  │  Custom Partitioner                                  │  │  │
│  │  │  Path: institution/event-type/version/date-time      │  │  │
│  │  └──────────────────────────────────────────────────────┘  │  │
│  │                                                             │  │
│  │  ┌──────────────────────────────────────────────────────┐  │  │
│  │  │  Bank-Specific PGP Encryption                        │  │  │
│  │  │  • BNK001: ASCII armor + PAN removed                 │  │  │
│  │  │  • BNK002: No PGP + PAN decrypted                    │  │  │
│  │  │  • BNK003: Binary PGP + PAN rekeyed                  │  │  │
│  │  │  • BNK004: ASCII armor + No PAN field                │  │  │
│  │  │  • BNK005: ASCII armor + PAN tokenized               │  │  │
│  │  └──────────────────────────────────────────────────────┘  │  │
│  └────────────────────────────────────────────────────────────┘  │
└────────────────────────┬─────────────────────────────────────────┘
                         │
                         ▼
         ┌────────────────────────────────────────────┐
         │   Object Storage (Multi-Bank Structure)    │
         │   • Local: MinIO                           │
         │   • Cloud: IBM COS                         │
         │                                            │
         │   bnk001/ (REMOVE + PGP ASCII)             │
         │     year=2026/month=02/.../*.jsonl.pgp     │
         │   bnk002/ (DECRYPT + NO PGP)               │
         │     year=2026/month=02/.../*.jsonl         │
         │   bnk003/ (REKEY + PGP BINARY)             │
         │     year=2026/month=02/.../*.jsonl.pgp     │
         │   bnk004/ (NONE + PGP ASCII)               │
         │     year=2026/month=02/.../*.jsonl.pgp     │
         │   bnk005/ (DECRYPT+TOKEN + PGP ASCII)      │
         │     year=2026/month=02/.../*.jsonl.pgp     │
         └────────────────────────────────────────────┘
```

---

## 2. Composants Techniques

### 2.1. Base: Confluent S3 Sink Connector

**Choix technique**: Extension du connecteur Confluent S3 Sink
- ✅ Mature, battle-tested
- ✅ Support natif S3/MinIO/IBM COS
- ✅ Gestion de la rotation de fichiers
- ✅ Extensible via SMT (Single Message Transforms)
- ✅ Support du partitioning personnalisé

### 2.2. Single Message Transforms (SMT) Personnalisés

Nous développerons 3 SMTs custom:

#### 2.2.1. `HeadersToPayloadTransform`
**Rôle**: Extraire les headers Kafka et les injecter dans le message

**Configuration paramétrable**:
```properties
transforms=headersToPayload
transforms.headersToPayload.type=com.banking.kafka.transforms.HeadersToPayloadTransform
transforms.headersToPayload.mandatory.headers=X-Institution-Id,X-Event-Type,X-Event-Version
transforms.headersToPayload.optional.headers=X-Original-Request-Id,X-Original-Correlation-Id,Original-Idempotency-Key,X-User-Id,X-User-Context-Id
transforms.headersToPayload.target.field=headers
```

**Output structure**:
```json
{
  "headers": {
    "X-Institution-Id": "BNK001",
    "X-Event-Type": "PAYMENT_AUTHORIZED",
    "X-Event-Version": "v1",
    "X-Original-Request-Id": "uuid-1234",
    ...
  },
  "payload": { ... original message ... }
}
```

#### 2.2.2. `PANTransformationSMT`
**Rôle**: Gérer le champ `encryptedPrimaryAccountNumber` selon la stratégie configurée (globale ou par banque)

**Stratégies**:
1. **REMOVE**: Supprimer le champ
2. **DECRYPT**: Déchiffrer JWE/RSA et exposer en clair dans `primaryAccountNumber`
3. **REKEY**: Transchiffrer avec la clé publique de la banque partenaire
4. **NONE**: Aucune transformation (pour messages sans PAN)

**Configuration globale (une stratégie pour tous)**:
```properties
transforms=panTransform
transforms.panTransform.type=com.banking.kafka.transforms.PANTransformationSMT
transforms.panTransform.strategy=REKEY  # REMOVE | DECRYPT | REKEY | NONE
transforms.panTransform.source.field=encryptedPrimaryAccountNumber
transforms.panTransform.target.field=primaryAccountNumber

# Pour DECRYPT et REKEY
transforms.panTransform.private.key.path=/keys/my-private-key.pem
transforms.panTransform.private.key.source=FILE  # FILE | IBM_KEY_PROTECT

# Pour REKEY uniquement
transforms.panTransform.partner.keys.mapping.path=/config/partner-keys-mapping.json
# Format du mapping: {"BNK001": "/keys/bnk001-public.pem", "BNK002": "..."}
transforms.panTransform.institution.header=X-Institution-Id
```

**Configuration multi-banques (recommandée)**:
```properties
transforms=panTransform
transforms.panTransform.type=com.banking.kafka.transforms.PANTransformationSMT
transforms.panTransform.bank.config.path=/config/banks/bank-config.json
transforms.panTransform.institution.id.header=X-Institution-Id
```

Avec `bank-config.json`:
```json
{
  "banks": {
    "BNK001": {
      "pan_strategy": "REMOVE",
      "pan_config": {
        "source_field": "encryptedPrimaryAccountNumber"
      }
    },
    "BNK002": {
      "pan_strategy": "DECRYPT",
      "pan_config": {
        "source_field": "encryptedPrimaryAccountNumber",
        "target_field": "primaryAccountNumber",
        "private_key_path": "/keys/bank-private-key.pem"
      }
    },
    "BNK005": {
      "pan_strategy": "DECRYPT",
      "pan_config": {
        "tokenize": true,
        "target_field": "tokenizedPAN"
      }
    }
  }
}
```

**Format JWE/RSA**:
- JWE Compact Serialization
- Algorithm: RSA-OAEP-256
- Encryption: A256GCM

#### 2.2.3. `JSONLFormatTransform`
**Rôle**: S'assurer que chaque record est une ligne JSON valide (JSONL format)

---

### 2.3. Custom Partitioner

**Classe**: `BankingHierarchicalPartitioner`

**Logique de partitioning**:
```
<institution-id>/<event-type>/<event-version>/year=<YYYY>/month=<MM>/day=<DD>/hour=<HH>/
```

Exemple:
```
BNK001/PAYMENT_AUTHORIZED/v1/year=2026/month=02/day=02/hour=14/payments-000001.jsonl
```

**Configuration**:
```properties
partitioner.class=com.banking.kafka.partitioner.BankingHierarchicalPartitioner
partitioner.institution.header=X-Institution-Id
partitioner.event.type.header=X-Event-Type
partitioner.event.version.header=X-Event-Version
```

---

### 2.4. Bank Configuration Manager

**Classe**: `BankConfigManager`

**Rôle**: Gérer les configurations spécifiques par banque pour la transformation PAN et le chiffrement PGP.

**Configuration centralisée**:
```json
{
  "banks": {
    "BNK001": {
      "name": "Banque Nationale",
      "pan_strategy": "REMOVE",
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
        "target_field": "primaryAccountNumber",
        "private_key_path": "/keys/bank-private-key.pem"
      },
      "pgp_encryption": {
        "enabled": false
      }
    },
    "BNK003": {
      "name": "Banque Internationale",
      "pan_strategy": "REKEY",
      "pgp_encryption": {
        "enabled": true,
        "armor": false
      }
    }
  }
}
```

**Stratégies par banque**:

| Banque | Stratégie PAN | PGP | Format | Use Case |
|--------|---------------|-----|--------|----------|
| BNK001 | REMOVE | ✅ | ASCII | Conformité stricte PCI-DSS |
| BNK002 | DECRYPT | ❌ | - | Système legacy nécessitant PAN clair |
| BNK003 | REKEY | ✅ | Binaire | Isolation avec clé propre |
| BNK004 | NONE | ✅ | ASCII | Banque utilisant uniquement tokens |
| BNK005 | DECRYPT+Token | ✅ | ASCII | Sécurité maximale (double chiffrement) |

**Configuration du connector**:
```properties
transforms.transformPANPerBank.type=com.banking.kafka.transforms.PANTransformationSMT
transforms.transformPANPerBank.bank.config.path=/config/banks/bank-config.json
transforms.transformPANPerBank.institution.id.header=X-Institution-Id
```

**Avantages**:
- Configuration centralisée pour toutes les banques
- Stratégies différentes par institution
- PGP optionnel et configurable par banque
- Ajout de nouvelles banques sans modifier le code

---

### 2.5. PGP Streaming Encryption (Bank-Specific)

**Activation conditionnelle par banque**:

Le chiffrement PGP est configuré individuellement pour chaque banque via `BankPGPEncryptor`.

**Configuration globale (legacy)**:
```properties
pgp.encryption.enabled=true
pgp.public.key.path=/keys/recipient-pgp-public.asc
pgp.armor=false  # Binary PGP pour performances
```

**Configuration multi-banques (recommandée)**:

Chaque banque définit dans `bank-config.json`:
```json
{
  "BNK001": {
    "pgp_encryption": {
      "enabled": true,
      "public_key_path": "/keys/pgp/bnk001-public.asc",
      "armor": true
    }
  },
  "BNK002": {
    "pgp_encryption": {
      "enabled": false
    }
  }
}
```

**Exemples de configuration**:
- **BNK001**: PGP ASCII armor (lisible, debugging facile)
- **BNK002**: Pas de PGP (chiffrement S3 suffisant)
- **BNK003**: PGP binaire (compact, performances)
- **BNK004**: PGP ASCII armor (conformité réglementaire)
- **BNK005**: PGP ASCII armor + double chiffrement S3

**Implémentation**:
- Wrapper autour de l'output stream S3 avec BouncyCastle PGP
- Cache des clés publiques par banque
- Sélection automatique du format (armor/binary) par banque

---

### 2.5. Rotation de Fichiers

**Stratégie**: Temporelle (paramétrable)

**Configuration**:
```properties
rotate.schedule.interval.ms=300000  # 5 minutes
flush.size=1000  # Flush aussi tous les 1000 records
```

---

## 3. Gestion des Clés de Chiffrement

### 3.1. Environnement Local (MinIO)

**Structure des clés (Multi-Banques)**:
```
config/local/keys/
├── my-institution/
│   ├── private-key.pem       # Clé privée RSA pour déchiffrement JWE
│   └── private-key-id.txt    # Key ID utilisé dans le JWE header
├── partner-banks/
│   ├── BNK001-public.pem     # Clé publique pour re-chiffrement
│   ├── BNK002-public.pem
│   ├── BNK003-public.pem
│   └── ...
└── pgp/
    ├── bnk001-public.asc     # Clé PGP BNK001 (ASCII armor)
    ├── bnk001-private.asc    # Pour tests de déchiffrement
    ├── bnk002-public.asc     # Clé PGP BNK002 (si activé)
    ├── bnk003-public.asc     # Clé PGP BNK003 (binary)
    ├── bnk004-public.asc     # Clé PGP BNK004
    ├── bnk005-public.asc     # Clé PGP BNK005
    └── ...
```

**Génération des clés PGP**:
```bash
#!/bin/bash
for bank in bnk001 bnk002 bnk003 bnk004 bnk005; do
  gpg --batch --gen-key <<EOF
Key-Type: RSA
Key-Length: 2048
Name-Real: Banking POC ${bank^^}
Name-Email: ${bank}@banking-poc.local
Expire-Date: 0
%no-protection
%commit
EOF
  gpg --armor --export "${bank}@banking-poc.local" > "keys/pgp/${bank}-public.asc"
  gpg --armor --export-secret-keys "${bank}@banking-poc.local" > "keys/pgp/${bank}-private.asc"
done
```

**Chargement**: Au démarrage du connector via filesystem, avec cache par banque

### 3.2. Environnement Cloud (IBM COS)

**IBM Key Protect Integration**:
- Clés RSA stockées dans IBM Key Protect
- Récupération via API Key Protect au runtime
- Caching des clés en mémoire (avec refresh périodique)

**Configuration**:
```properties
key.storage.provider=IBM_KEY_PROTECT  # FILE | IBM_KEY_PROTECT
ibm.key.protect.instance.id=<instance-id>
ibm.key.protect.api.key=<api-key>
ibm.key.protect.region=eu-de
ibm.key.protect.private.key.id=<key-id>
```

---

## 4. Format de Sortie JSONL

### 4.1. Structure d'un Record

```json
{"headers":{"X-Institution-Id":"BNK001","X-Event-Type":"PAYMENT_AUTHORIZED","X-Event-Version":"v1","X-Original-Request-Id":"req-123","X-User-Id":"user-456"},"payload":{"transactionId":"txn-789","amount":150.00,"currency":"EUR","merchantId":"merch-001","timestamp":"2026-02-02T14:30:00Z","primaryAccountNumber":"1234567890123456"}}
{"headers":{"X-Institution-Id":"BNK001","X-Event-Type":"PAYMENT_AUTHORIZED","X-Event-Version":"v1"},"payload":{"transactionId":"txn-790","amount":75.50,"currency":"EUR","merchantId":"merch-002","timestamp":"2026-02-02T14:31:00Z"}}
```

**Caractéristiques**:
- Une ligne JSON = un message Kafka
- Pas de retours à la ligne dans le JSON (compact)
- Pas de tableau englobant (streaming friendly)
- Headers optionnels omis si absents

---

## 5. Multi-Tenancy et Routage

### 5.1. Gestion Multi-Institutions

**Principe**: Un seul connector lit le topic, mais applique des stratégies différentes par institution

**Header obligatoire**: `X-Institution-Id`
- Si absent → Dead Letter Queue (DLQ)
- Utilisé pour:
  - Sélection de la configuration spécifique de la banque
  - Partitioning dans le COS
  - Sélection de la clé PGP appropriée
  - Choix de la stratégie de transformation PAN

**Architecture multi-banques**:

```
Message Kafka avec X-Institution-Id: BNK001
    ↓
BankConfigManager charge config BNK001
    ↓
Stratégie PAN: REMOVE
    ↓
PGP: Enabled (ASCII armor)
    ↓
Stockage: bnk001/year=2026/.../file.jsonl.pgp
```

**Exemples de configurations par banque**:

| Institution | Stratégie PAN | PGP | Chemin Stockage | Raison |
|-------------|---------------|-----|-----------------|--------|
| BNK001 | REMOVE | ✅ ASCII | `bnk001/...` | Conformité stricte |
| BNK002 | DECRYPT | ❌ | `bnk002/...` | Système legacy |
| BNK003 | REKEY | ✅ Binary | `bnk003/...` | Isolation données |
| BNK004 | NONE | ✅ ASCII | `bnk004/...` | Pas de PAN |
| BNK005 | DECRYPT+Token | ✅ ASCII | `bnk005/...` | Sécurité max |

### 5.2. Dead Letter Queue

**Configuration**:
```properties
errors.tolerance=all
errors.deadletterqueue.topic.name=banking-dlq
errors.deadletterqueue.context.headers.enable=true
```

**Cas d'erreur DLQ**:
- Header `X-Institution-Id` manquant
- Header `X-Event-Type` ou `X-Event-Version` manquant
- Échec du déchiffrement JWE
- Message Kafka invalide (pas du JSON)

---

## 6. Déploiement

### 6.1. Environnement Local

**Stack Docker Compose**:
- Zookeeper
- Kafka (1 broker)
- Kafka Connect (distributed mode)
- MinIO
- Schema Registry (optionnel)

**Commande de lancement**:
```bash
cd docker
docker-compose up -d
```

### 6.2. Environnement Cloud IBM

**Infrastructure**:
- IBM Event Streams (Kafka managé)
- IBM Kubernetes Service (IKS) ou OpenShift
  - Kafka Connect workers en pods
  - ConfigMaps pour configuration
  - Secrets pour clés sensibles
- IBM Cloud Object Storage (COS)
- IBM Key Protect pour gestion des clés

**Pipeline CI/CD**:
- Build de l'image Docker custom avec le connector
- Déploiement via Helm charts
- Configuration via GitOps (ArgoCD)

---

## 7. Configuration Paramétrable

### 7.1. Fichier `connector.properties`

Toutes les configurations sont externalisées:

```properties
# === CONNECTOR BASE ===
name=banking-s3-sink-connector
connector.class=io.confluent.connect.s3.S3SinkConnector
tasks.max=3

# === KAFKA ===
topics=payments-in
consumer.override.auto.offset.reset=earliest

# === S3/COS ===
s3.bucket.name=banking-payments-output
s3.region=eu-de
store.url=http://localhost:9000  # MinIO local
aws.access.key.id=minioadmin
aws.secret.access.key=minioadmin

# === FORMAT ===
format.class=io.confluent.connect.s3.format.json.JsonFormat
flush.size=1000
rotate.schedule.interval.ms=300000

# === PARTITIONING ===
partitioner.class=com.banking.kafka.partitioner.BankingHierarchicalPartitioner
partitioner.institution.header=X-Institution-Id
partitioner.event.type.header=X-Event-Type
partitioner.event.version.header=X-Event-Version

# === TRANSFORMS ===
transforms=headersToPayload,panTransform,jsonlFormat

transforms.headersToPayload.type=com.banking.kafka.transforms.HeadersToPayloadTransform
transforms.headersToPayload.mandatory.headers=X-Institution-Id,X-Event-Type,X-Event-Version
transforms.headersToPayload.optional.headers=X-Original-Request-Id,X-Original-Correlation-Id,Original-Idempotency-Key,X-User-Id,X-User-Context-Id

transforms.panTransform.type=com.banking.kafka.transforms.PANTransformationSMT
transforms.panTransform.strategy=DECRYPT
transforms.panTransform.private.key.path=/keys/my-institution/private-key.pem

transforms.jsonlFormat.type=com.banking.kafka.transforms.JSONLFormatTransform

# === PGP (OPTIONAL) ===
pgp.encryption.enabled=false

# === ERROR HANDLING ===
errors.tolerance=all
errors.deadletterqueue.topic.name=banking-dlq
errors.deadletterqueue.context.headers.enable=true
```

---

## 8. Phases d'Implémentation

### Phase 1: Setup de l'environnement ✅
- ✅ Structure du projet Maven
- ✅ Docker Compose (Kafka + MinIO)
- ✅ Dépendances (Kafka Connect, Nimbus JOSE, BouncyCastle)

### Phase 2: SMT - HeadersToPayloadTransform ✅
- ✅ Implémentation du transform
- ✅ Tests unitaires (15 tests)
- ✅ Support headers obligatoires/optionnels

### Phase 3: SMT - PANTransformationSMT ✅
- ✅ Mode REMOVE
- ✅ Mode DECRYPT (JWE/RSA déchiffrement)
- ✅ Mode REKEY (transchiffrement)
- ✅ Gestion des clés (FILE provider)
- ✅ Tests unitaires (12 tests)

### Phase 4: Custom Partitioner ✅
- ✅ BankingHierarchicalPartitioner
- ✅ Partitioning hiérarchique institution/event/version/date
- ✅ Tests unitaires (4 tests)

### Phase 5: PGP Encryption ✅
- ✅ PGPEncryptionHandler avec BouncyCastle
- ✅ Support ASCII armor et binaire
- ✅ Tests de chiffrement/déchiffrement

### Phase 6: Configuration Multi-Banques ✅
- ✅ BankConfigManager pour gestion centralisée
- ✅ Configuration JSON par banque (bank-config.json)
- ✅ BankPGPEncryptor pour chiffrement PGP par banque
- ✅ MultiBankPaymentProducer pour tests
- ✅ 5 scénarios de banques (BNK001-BNK005)
- ✅ Documentation complète (MULTI_BANK_SETUP.md)

### Phase 7: Testing E2E ✅
- ✅ 31 tests unitaires passing
- ✅ Docker Compose E2E setup
- ✅ Test producers pour tous les scénarios

### Phase 8: Cloud Deployment (À venir)
- ⏳ Configuration IBM COS
- ⏳ Intégration IBM Key Protect
- ⏳ Déploiement sur IKS/OpenShift

---

## 9. Considérations de Sécurité

### 9.1. PAN Handling (PCI-DSS)
- ⚠️ **Mode DECRYPT**: Le PAN en clair n'existe qu'en mémoire, jamais logué
- Rotation régulière des clés RSA
- Audit trail de tous les déchiffrements

### 9.2. Clés de Chiffrement
- Clés privées jamais committées dans Git (.gitignore)
- Utilisation de secrets Kubernetes en production
- Principe du moindre privilège pour Key Protect

### 9.3. Transport
- Kafka: TLS + SASL activés en production
- COS: HTTPS uniquement
- Key Protect: API Key avec IAM policies restrictives

---

## 10. Monitoring et Observabilité

### 10.1. Métriques Kafka Connect
- Consumer lag par partition
- Throughput (records/sec)
- Taux d'erreur et DLQ
- Latence de traitement

### 10.2. Métriques Custom
- Nombre de PANs déchiffrés/supprimés/re-chiffrés par institution
- Taille des fichiers générés
- Latence de rotation de fichiers

### 10.3. Logs
- Log des erreurs de déchiffrement (sans exposer le PAN)
- Audit des accès aux clés (Key Protect)
- Traçabilité via X-Original-Request-Id

---

## 11. Évolutions Futures

- Support d'autres formats de sortie (Parquet, Avro)
- Compression des fichiers (gzip, snappy)
- Support de schémas Avro avec Schema Registry
- Tokenization du PAN (alternative au chiffrement)
- Support multi-cloud (AWS S3, Azure Blob)

---

## Annexes

### A. Dépendances Principales

```xml
<!-- Kafka Connect API -->
<dependency>
    <groupId>org.apache.kafka</groupId>
    <artifactId>connect-api</artifactId>
</dependency>

<!-- Confluent S3 Sink -->
<dependency>
    <groupId>io.confluent</groupId>
    <artifactId>kafka-connect-s3</artifactId>
</dependency>

<!-- JWE/RSA -->
<dependency>
    <groupId>com.nimbusds</groupId>
    <artifactId>nimbus-jose-jwt</artifactId>
</dependency>

<!-- PGP -->
<dependency>
    <groupId>org.bouncycastle</groupId>
    <artifactId>bcpg-jdk18on</artifactId>
</dependency>

<!-- IBM COS SDK -->
<dependency>
    <groupId>com.ibm.cos</groupId>
    <artifactId>ibm-cos-java-sdk</artifactId>
</dependency>
```

### B. Références
- [Confluent S3 Sink Connector Documentation](https://docs.confluent.io/kafka-connect-s3-sink/current/)
- [Kafka Connect SMT API](https://kafka.apache.org/documentation/#connect_transforms)
- [IBM Key Protect API](https://cloud.ibm.com/docs/key-protect)
- [JWE Specification (RFC 7516)](https://tools.ietf.org/html/rfc7516)
