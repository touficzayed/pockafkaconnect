# Guide de Configuration

Ce document détaille tous les paramètres de configuration du Banking Kafka Connect POC.

## Table des matières

1. [Configuration du Connector](#configuration-du-connector)
2. [Single Message Transforms (SMT)](#single-message-transforms-smt)
3. [Configuration Multi-Banques](#configuration-multi-banques)
4. [Partitionner](#partitionner)
5. [Gestion des Clés](#gestion-des-clés)
6. [PGP Encryption](#pgp-encryption)
7. [Gestion des Erreurs](#gestion-des-erreurs)
8. [Performance Tuning](#performance-tuning)

---

## Configuration du Connector

### Paramètres de Base

| Paramètre | Type | Défaut | Description |
|-----------|------|--------|-------------|
| `name` | string | required | Nom unique du connector |
| `connector.class` | string | required | `io.confluent.connect.s3.S3SinkConnector` |
| `tasks.max` | int | 1 | Nombre de tâches parallèles |
| `topics` | string | required | Topics Kafka à consommer (séparés par virgules) |

### Configuration Kafka

| Paramètre | Type | Défaut | Description |
|-----------|------|--------|-------------|
| `consumer.override.auto.offset.reset` | string | latest | `earliest` ou `latest` |
| `consumer.override.max.poll.records` | int | 500 | Nombre max de records par poll |
| `consumer.override.security.protocol` | string | PLAINTEXT | `PLAINTEXT`, `SASL_SSL`, etc. |

### Configuration S3/COS

| Paramètre | Type | Défaut | Description |
|-----------|------|--------|-------------|
| `s3.bucket.name` | string | required | Nom du bucket S3/COS |
| `s3.region` | string | required | Région AWS/IBM (ex: `eu-de`) |
| `store.url` | string | - | URL du service S3 (pour MinIO/IBM COS) |
| `aws.access.key.id` | string | required | Access Key ID (HMAC) |
| `aws.secret.access.key` | string | required | Secret Access Key (HMAC) |
| `s3.path.style.access.enabled` | boolean | false | `true` pour MinIO, `false` pour IBM COS |
| `s3.sse.algorithm` | string | - | Chiffrement côté serveur (`AES256`) |

### Format et Rotation

| Paramètre | Type | Défaut | Description |
|-----------|------|--------|-------------|
| `format.class` | string | required | `io.confluent.connect.s3.format.json.JsonFormat` |
| `rotate.schedule.interval.ms` | int | 60000 | Intervalle de rotation en ms |
| `flush.size` | int | 1000 | Nombre de records avant flush |
| `filename.offset.zero.pad.width` | int | 10 | Padding des offsets dans le nom de fichier |

---

## Single Message Transforms (SMT)

### 1. HeadersToPayloadTransform

Extrait les headers Kafka et les ajoute au message.

#### Configuration

```properties
transforms=headersToPayload
transforms.headersToPayload.type=com.banking.kafka.transforms.HeadersToPayloadTransform
```

#### Paramètres

| Paramètre | Type | Défaut | Description |
|-----------|------|--------|-------------|
| `mandatory.headers` | string | required | Headers obligatoires (CSV) |
| `optional.headers` | string | "" | Headers optionnels (CSV) |
| `target.field` | string | "headers" | Nom du champ cible dans le JSON |
| `fail.on.missing.mandatory` | boolean | true | Fail si un header obligatoire manque |

#### Exemple

```properties
transforms.headersToPayload.mandatory.headers=X-Institution-Id,X-Event-Type,X-Event-Version
transforms.headersToPayload.optional.headers=X-Original-Request-Id,X-User-Id
transforms.headersToPayload.target.field=headers
transforms.headersToPayload.fail.on.missing.mandatory=true
```

**Input:**
```json
Headers: {X-Institution-Id: BNK001, X-Event-Type: PAYMENT}
Payload: {transactionId: "txn-123", amount: 100}
```

**Output:**
```json
{
  "headers": {
    "X-Institution-Id": "BNK001",
    "X-Event-Type": "PAYMENT"
  },
  "payload": {
    "transactionId": "txn-123",
    "amount": 100
  }
}
```

---

### 2. PANTransformationSMT

Gère la transformation du PAN chiffré (JWE).

#### Configuration

```properties
transforms=panTransform
transforms.panTransform.type=com.banking.kafka.transforms.PANTransformationSMT
```

#### Paramètres Communs

| Paramètre | Type | Défaut | Description |
|-----------|------|--------|-------------|
| `strategy` | string | required | `REMOVE`, `DECRYPT`, ou `REKEY` |
| `source.field` | string | required | Champ source contenant le PAN chiffré |
| `target.field` | string | required | Champ cible pour le résultat |

#### Mode REMOVE

Supprime simplement le champ contenant le PAN.

```properties
transforms.panTransform.strategy=REMOVE
transforms.panTransform.source.field=encryptedPrimaryAccountNumber
```

#### Mode DECRYPT

Déchiffre le JWE et expose le PAN en clair.

```properties
transforms.panTransform.strategy=DECRYPT
transforms.panTransform.source.field=encryptedPrimaryAccountNumber
transforms.panTransform.target.field=primaryAccountNumber

# Key management
transforms.panTransform.key.storage.provider=FILE
transforms.panTransform.private.key.path=/keys/my-institution/private-key.pem
transforms.panTransform.private.key.id=my-key-2026-01
```

**Paramètres spécifiques:**

| Paramètre | Type | Description |
|-----------|------|-------------|
| `key.storage.provider` | string | `FILE` ou `IBM_KEY_PROTECT` |
| `private.key.path` | string | Chemin vers la clé privée RSA (si FILE) |
| `private.key.id` | string | ID de la clé (pour logs d'audit) |

#### Mode REKEY

Transchiffre le PAN avec la clé publique d'une banque partenaire.

```properties
transforms.panTransform.strategy=REKEY
transforms.panTransform.source.field=encryptedPrimaryAccountNumber
transforms.panTransform.target.field=encryptedPrimaryAccountNumber

# Key management
transforms.panTransform.key.storage.provider=FILE
transforms.panTransform.private.key.path=/keys/my-institution/private-key.pem

# Partner keys
transforms.panTransform.partner.keys.mapping.path=/config/partner-keys-mapping.json
transforms.panTransform.institution.header=X-Institution-Id
```

**Paramètres spécifiques:**

| Paramètre | Type | Description |
|-----------|------|-------------|
| `partner.keys.mapping.path` | string | Chemin vers le fichier de mapping JSON |
| `partner.keys.mapping.provider` | string | `FILE` ou `COS` |
| `partner.keys.mapping.bucket` | string | Bucket COS (si provider=COS) |
| `partner.keys.cache.ttl.seconds` | int | TTL du cache des clés (défaut: 3600) |
| `institution.header` | string | Header contenant l'institution ID |

**Format du fichier de mapping:**

```json
{
  "keys": {
    "BNK001": {
      "publicKeyPath": "/keys/partners/bnk001-public.pem",
      "keyId": "bnk001-key-2026-01",
      "enabled": true
    },
    "BNK002": {
      "publicKeyPath": "/keys/partners/bnk002-public.pem",
      "keyId": "bnk002-key-2026-01",
      "enabled": true
    }
  },
  "default": {
    "strategy": "REMOVE"
  }
}
```

#### JWE Configuration

| Paramètre | Type | Défaut | Description |
|-----------|------|--------|-------------|
| `jwe.algorithm` | string | RSA-OAEP-256 | Algorithme JWE (RSA-OAEP, RSA-OAEP-256) |
| `jwe.encryption` | string | A256GCM | Encryption method (A256GCM, A128GCM) |

---

### 3. JSONLFormatTransform

Assure le format JSONL (une ligne JSON par record).

```properties
transforms=jsonlFormat
transforms.jsonlFormat.type=com.banking.kafka.transforms.JSONLFormatTransform
transforms.jsonlFormat.compact=true
```

| Paramètre | Type | Défaut | Description |
|-----------|------|--------|-------------|
| `compact` | boolean | true | Supprimer les espaces inutiles |

---

## Configuration Multi-Banques

Le système supporte la configuration spécifique par banque pour gérer différentes stratégies de transformation PAN et de chiffrement PGP.

### Activation

Pour utiliser la configuration multi-banques, configurez le PANTransformationSMT avec un fichier de configuration JSON:

```properties
transforms=extractHeaders,transformPANPerBank
transforms.extractHeaders.type=com.banking.kafka.transforms.HeadersToPayloadTransform
transforms.extractHeaders.mandatory.headers=X-Institution-Id,X-Event-Type

transforms.transformPANPerBank.type=com.banking.kafka.transforms.PANTransformationSMT
transforms.transformPANPerBank.bank.config.path=/config/banks/bank-config.json
transforms.transformPANPerBank.institution.id.header=X-Institution-Id
```

### Structure du Fichier de Configuration

Fichier JSON définissant les configurations spécifiques par banque (`/config/banks/bank-config.json`):

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
        "armor": true,
        "reason": "Chiffrement PGP obligatoire"
      },
      "s3_config": {
        "bucket": "banking-payments",
        "path_prefix": "bnk001"
      }
    },
    "BNK002": {
      "name": "Crédit Populaire",
      "pan_strategy": "DECRYPT",
      "pan_config": {
        "source_field": "encryptedPrimaryAccountNumber",
        "target_field": "primaryAccountNumber",
        "private_key_path": "/keys/bank-private-key.pem",
        "reason": "Système legacy nécessitant PAN en clair"
      },
      "pgp_encryption": {
        "enabled": false,
        "reason": "Chiffrement S3 suffisant"
      }
    }
  },
  "default": {
    "name": "Configuration par défaut",
    "pan_strategy": "REMOVE",
    "pgp_encryption": {
      "enabled": false
    }
  }
}
```

### Paramètres de Configuration par Banque

#### Configuration PAN

| Paramètre | Type | Description |
|-----------|------|-------------|
| `name` | string | Nom de la banque (informatif) |
| `pan_strategy` | string | Stratégie: `REMOVE`, `DECRYPT`, `REKEY`, `NONE` |
| `pan_config.source_field` | string | Champ source contenant le PAN chiffré |
| `pan_config.target_field` | string | Champ cible (pour DECRYPT/REKEY) |
| `pan_config.private_key_path` | string | Chemin vers la clé privée (pour DECRYPT/REKEY) |
| `pan_config.tokenize` | boolean | Tokeniser le PAN après déchiffrement |
| `pan_config.reason` | string | Raison de la stratégie (audit/documentation) |

#### Configuration PGP

| Paramètre | Type | Défaut | Description |
|-----------|------|--------|-------------|
| `pgp_encryption.enabled` | boolean | false | Activer le chiffrement PGP |
| `pgp_encryption.public_key_path` | string | - | Chemin vers la clé publique PGP |
| `pgp_encryption.armor` | boolean | true | ASCII armor (true) ou binaire (false) |
| `pgp_encryption.reason` | string | - | Raison du chiffrement (audit) |

#### Configuration S3

| Paramètre | Type | Description |
|-----------|------|-------------|
| `s3_config.bucket` | string | Nom du bucket S3 |
| `s3_config.path_prefix` | string | Préfixe du chemin (ex: `bnk001`) |

### Scénarios par Banque

#### Scénario 1: BNK001 - Suppression PAN + PGP ASCII

**Use Case:** Conformité stricte PCI-DSS, aucun PAN stocké

```json
{
  "BNK001": {
    "pan_strategy": "REMOVE",
    "pgp_encryption": {
      "enabled": true,
      "armor": true
    }
  }
}
```

**Résultat:** PAN supprimé, fichier chiffré PGP format texte

---

#### Scénario 2: BNK002 - Déchiffrement PAN + Sans PGP

**Use Case:** Système legacy nécessitant PAN en clair

```json
{
  "BNK002": {
    "pan_strategy": "DECRYPT",
    "pan_config": {
      "target_field": "primaryAccountNumber",
      "private_key_path": "/keys/bank-private-key.pem"
    },
    "pgp_encryption": {
      "enabled": false
    }
  }
}
```

**Résultat:** PAN déchiffré exposé, fichier non chiffré PGP

---

#### Scénario 3: BNK003 - Re-chiffrement + PGP Binaire

**Use Case:** Isolation des données avec clé propre

```json
{
  "BNK003": {
    "pan_strategy": "REKEY",
    "pan_config": {
      "private_key_path": "/keys/bank-private-key.pem",
      "partner_keys_mapping": {
        "BNK003": "/keys/partners/bnk003-public.pem"
      }
    },
    "pgp_encryption": {
      "enabled": true,
      "armor": false
    }
  }
}
```

**Résultat:** PAN re-chiffré avec clé BNK003, fichier PGP binaire compact

---

#### Scénario 4: BNK004 - Pas de PAN + PGP

**Use Case:** Banque utilisant uniquement des tokens

```json
{
  "BNK004": {
    "pan_strategy": "NONE",
    "pgp_encryption": {
      "enabled": true,
      "armor": true
    }
  }
}
```

**Résultat:** Aucune transformation PAN, fichier chiffré PGP

---

#### Scénario 5: BNK005 - Déchiffrement + Tokenisation + Double Chiffrement

**Use Case:** Sécurité maximale PCI-DSS Level 1

```json
{
  "BNK005": {
    "pan_strategy": "DECRYPT",
    "pan_config": {
      "target_field": "tokenizedPAN",
      "tokenize": true,
      "private_key_path": "/keys/bank-private-key.pem"
    },
    "pgp_encryption": {
      "enabled": true,
      "armor": true
    }
  }
}
```

**Résultat:** PAN déchiffré puis tokenisé, double chiffrement (PGP + S3)

---

### Tableau Récapitulatif

| Banque | Stratégie PAN | PGP | Format PGP | Use Case |
|--------|---------------|-----|------------|----------|
| BNK001 | REMOVE | ✅ | ASCII | Conformité stricte |
| BNK002 | DECRYPT | ❌ | - | Système legacy |
| BNK003 | REKEY | ✅ | Binaire | Isolation des données |
| BNK004 | NONE | ✅ | ASCII | Tokens uniquement |
| BNK005 | DECRYPT+Token | ✅ | ASCII | Sécurité maximale |

### Configuration du Connector Multi-Banques

#### Option 1: Un Connecteur pour Toutes les Banques

```properties
name=banking-s3-sink-multibank
connector.class=io.confluent.connect.s3.S3SinkConnector
topics=payments-in

transforms=extractHeaders,transformPANPerBank,addBankPrefix

transforms.extractHeaders.type=com.banking.kafka.transforms.HeadersToPayloadTransform
transforms.extractHeaders.mandatory.headers=X-Institution-Id,X-Event-Type
transforms.extractHeaders.target.field=metadata

transforms.transformPANPerBank.type=com.banking.kafka.transforms.PANTransformationSMT
transforms.transformPANPerBank.bank.config.path=/config/banks/bank-config.json
transforms.transformPANPerBank.institution.id.header=X-Institution-Id

s3.bucket.name=banking-payments
partitioner.class=com.banking.kafka.partitioner.BankingHierarchicalPartitioner
```

#### Option 2: Un Connecteur par Banque

Pour BNK001 (REMOVE strategy):

```properties
name=banking-s3-sink-bnk001
connector.class=io.confluent.connect.s3.S3SinkConnector
topics=payments-in

transforms=extractHeaders,transformPAN

transforms.transformPAN.type=com.banking.kafka.transforms.PANTransformationSMT
transforms.transformPAN.strategy=REMOVE
transforms.transformPAN.source.field=encryptedPrimaryAccountNumber

s3.bucket.name=banking-payments
s3.object.key.template=bnk001/year={{yyyy}}/month={{MM}}/day={{dd}}/{{topic}}-{{partition}}-{{start_offset}}.json
```

### Tests Multi-Banques

Producer de test pour toutes les banques:

```bash
# Compiler et envoyer des messages pour toutes les banques
mvn clean package
java -jar target/kafka-connect-banking-poc-1.0-SNAPSHOT-jar-with-dependencies.jar \
  com.banking.kafka.integration.MultiBankPaymentProducer \
  localhost:9092 payments-in 10

# Ou pour une banque spécifique
java -jar target/kafka-connect-banking-poc-1.0-SNAPSHOT-jar-with-dependencies.jar \
  com.banking.kafka.integration.MultiBankPaymentProducer \
  localhost:9092 payments-in 50 BNK002
```

### Vérification des Résultats

```bash
# Lister les fichiers par banque
docker exec banking-minio-init mc find minio/banking-payments/bnk001 --name "*.json"
docker exec banking-minio-init mc find minio/banking-payments/bnk002 --name "*.json"

# Voir le contenu (BNK002 sans PGP)
docker exec banking-minio-init mc cat minio/banking-payments/bnk002/.../file.json | jq .

# Déchiffrer un fichier PGP (BNK001)
docker exec banking-minio-init mc cp \
  minio/banking-payments/bnk001/.../file.json \
  /tmp/encrypted.json
gpg --decrypt /tmp/encrypted.json | jq .
```

---

## Partitionner

### BankingHierarchicalPartitioner

Organise les fichiers selon: `institution/event-type/version/date-time/`

Supporte deux modes de partitioning Kafka (côté producteur) :

1. **Mapping CSV déterministe** : assigne explicitement chaque banque à une partition via un fichier CSV
2. **Murmur2 hashing** (fallback) : hachage consistant identique au `DefaultPartitioner` de Kafka

```properties
partitioner.class=com.banking.kafka.partitioner.BankingHierarchicalPartitioner
```

#### Paramètres

| Paramètre | Type | Défaut | Description |
|-----------|------|--------|-------------|
| `partitioner.institution.header` | string | required | Header contenant l'institution ID |
| `partitioner.event.type.header` | string | required | Header contenant le type d'événement |
| `partitioner.event.version.header` | string | required | Header contenant la version |
| `partitioner.default.institution` | string | UNKNOWN | Valeur par défaut si header absent |
| `partitioner.default.event.type` | string | UNCLASSIFIED | Valeur par défaut si header absent |
| `partitioner.default.event.version` | string | v0 | Valeur par défaut si header absent |
| `bank.partition.mapping.file` | string | - | Chemin vers le fichier CSV de mapping banque→partition |

#### Fichier de mapping CSV

Format: une ligne par banque, `bankCode,partitionNumber`. Commentaires avec `#`.

```csv
# Mapping déterministe 200 banques → 20 partitions
BNK001,0
BNK002,1
BNK003,2
# ...
BNK200,19
```

Les banques non listées dans le CSV sont routées par Murmur2 hashing.

Pour générer un mapping équilibré:
```bash
for i in $(seq 1 200); do printf "BNK%03d,%d\n" $i $(( (i-1) % 20 )); done > bank-partition-mapping.csv
```

#### Exemple de chemin généré

```
BNK001/PAYMENT_AUTHORIZED/v1/year=2026/month=02/day=02/hour=14/payments-000001.jsonl
```

#### Scaling (20 partitions / 20 tasks)

Avec 200 banques et 20 tasks, chaque task gère ~10 banques:
- ~60 fichiers ouverts par task (10 banques × 3 event types × 2 versions)
- ~150 MB de buffers mémoire par task
- Heap JVM recommandé: 2-3 GB par worker

---

## Gestion des Clés

### FILE Provider (Local/Dev)

Clés stockées sur le filesystem local.

```properties
transforms.panTransform.key.storage.provider=FILE
transforms.panTransform.private.key.path=/keys/my-institution/private-key.pem
```

**Structure attendue:**
```
/keys/
├── my-institution/
│   ├── private-key.pem
│   └── key-id.txt
└── partners/
    ├── bnk001-public.pem
    └── bnk002-public.pem
```

### IBM_KEY_PROTECT Provider (Cloud/Prod)

Intégration avec IBM Key Protect.

```properties
transforms.panTransform.key.storage.provider=IBM_KEY_PROTECT
transforms.panTransform.ibm.key.protect.instance.id=${IBM_KEY_PROTECT_INSTANCE_ID}
transforms.panTransform.ibm.key.protect.api.key=${IBM_KEY_PROTECT_API_KEY}
transforms.panTransform.ibm.key.protect.region=eu-de
transforms.panTransform.ibm.key.protect.private.key.id=${PRIVATE_KEY_ID}
```

**Paramètres IBM Key Protect:**

| Paramètre | Type | Description |
|-----------|------|-------------|
| `ibm.key.protect.instance.id` | string | ID de l'instance Key Protect |
| `ibm.key.protect.api.key` | string | API Key IBM Cloud IAM |
| `ibm.key.protect.region` | string | Région (ex: `eu-de`, `us-south`) |
| `ibm.key.protect.private.key.id` | string | ID de la clé dans Key Protect |

---

## PGP Encryption

Chiffrement streaming des fichiers avec PGP via `PGPOutputStreamWrapper`.

Les données sont chiffrées à la volée sans charger le fichier entier en mémoire.
Empreinte mémoire: ~8 KB de buffer par stream, indépendamment de la taille du fichier.

### Modes d'utilisation

**Streaming (recommandé pour S3 Sink)**:
```java
// Wraps l'OutputStream S3 avec chiffrement PGP transparent
OutputStream out = bankPGPEncryptor.createStreamingEncryptorForBank("BNK001", s3OutputStream);
out.write(recordBytes); // Chiffré à la volée
out.close();
```

**Batch (rétrocompatibilité)**:
```java
byte[] encrypted = pgpHandler.encrypt(data, publicKey, armor);
```

### Activation

```properties
pgp.encryption.enabled=true
```

### Configuration FILE Provider

```properties
pgp.encryption.enabled=true
pgp.public.key.path=/keys/pgp/recipient-public.asc
pgp.armor=false
pgp.compression=ZIP
```

### Configuration IBM_KEY_PROTECT Provider

```properties
pgp.encryption.enabled=true
pgp.public.key.provider=IBM_KEY_PROTECT
pgp.public.key.id=${IBM_KEY_PROTECT_PGP_KEY_ID}
pgp.armor=false
pgp.compression=ZIP
```

### Paramètres

| Paramètre | Type | Défaut | Description |
|-----------|------|--------|-------------|
| `pgp.encryption.enabled` | boolean | false | Activer le chiffrement PGP |
| `pgp.public.key.provider` | string | FILE | `FILE` ou `IBM_KEY_PROTECT` |
| `pgp.public.key.path` | string | - | Chemin vers la clé publique (si FILE) |
| `pgp.public.key.id` | string | - | ID de la clé (si IBM_KEY_PROTECT) |
| `pgp.armor` | boolean | false | ASCII armor (true) ou binaire (false) |
| `pgp.compression` | string | ZIP | Compression (`NONE`, `ZIP`, `ZLIB`, `BZIP2`) |

**Note:** Fichiers générés avec extension `.pgp` si activé.

---

## Gestion des Erreurs

### Tolérance aux erreurs

```properties
errors.tolerance=all
errors.log.enable=true
errors.log.include.messages=true
```

| Paramètre | Type | Défaut | Description |
|-----------|------|--------|-------------|
| `errors.tolerance` | string | none | `none` (fail) ou `all` (continue) |
| `errors.log.enable` | boolean | false | Logger les erreurs |
| `errors.log.include.messages` | boolean | false | Inclure le message dans les logs |

### Dead Letter Queue

```properties
errors.deadletterqueue.topic.name=banking-payments-dlq
errors.deadletterqueue.topic.replication.factor=1
errors.deadletterqueue.context.headers.enable=true
```

| Paramètre | Type | Description |
|-----------|------|-------------|
| `errors.deadletterqueue.topic.name` | string | Nom du topic DLQ |
| `errors.deadletterqueue.topic.replication.factor` | int | Facteur de réplication |
| `errors.deadletterqueue.context.headers.enable` | boolean | Ajouter le contexte d'erreur |

### Retry

```properties
errors.retry.timeout=60000
errors.retry.delay.max.ms=5000
```

| Paramètre | Type | Défaut | Description |
|-----------|------|--------|-------------|
| `errors.retry.timeout` | int | 0 | Timeout total des retries (ms) |
| `errors.retry.delay.max.ms` | int | 60000 | Délai max entre retries (ms) |

---

## Performance Tuning

### Batch Processing

```properties
flush.size=100        # Réduit pour 200+ banques (limite les buffers mémoire par fichier)
rotate.schedule.interval.ms=60000  # 1 minute pour libérer les buffers plus vite
s3.part.size=5242880
```

| Paramètre | Type | Défaut | Description |
|-----------|------|--------|-------------|
| `flush.size` | int | 1000 | Records par fichier avant flush. Réduire à 100-200 pour 200+ banques |
| `rotate.schedule.interval.ms` | int | 60000 | Rotation temporelle (ms). Réduire pour libérer les buffers |
| `s3.part.size` | int | 5242880 | Taille des parts S3 multipart (bytes) |
| `tasks.max` | int | 1 | 20 recommandé pour 200 banques (10 banques/task) |

### Profils mémoire par nombre de banques

| Banques | tasks.max | flush.size | rotate.ms | Heap/worker |
|---------|-----------|------------|-----------|-------------|
| 5 | 3 | 1000 | 300000 | 1 GB |
| 50 | 10 | 500 | 120000 | 2 GB |
| 200 | 20 | 100 | 60000 | 3 GB |

### Consumer

```properties
consumer.override.max.poll.records=500
consumer.override.fetch.min.bytes=1024
```

---

## Audit et Monitoring

### Audit PAN (PCI-DSS)

```properties
banking.audit.enabled=true
banking.pci.compliant=true
banking.pci.mask.pan.in.logs=true
```

### Métriques

```properties
metrics.recording.level=INFO
```

En production (IBM Cloud):

```properties
metrics.exporter=SYSDIG
metrics.sysdig.endpoint=${SYSDIG_ENDPOINT}
metrics.sysdig.api.key=${SYSDIG_API_KEY}
```

---

## Variables d'Environnement

Pour sécuriser les secrets, utiliser des variables d'environnement:

```properties
# Kafka
consumer.override.sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required username="${KAFKA_USERNAME}" password="${KAFKA_PASSWORD}";

# COS
aws.access.key.id=${IBM_COS_ACCESS_KEY_ID}
aws.secret.access.key=${IBM_COS_SECRET_ACCESS_KEY}

# Key Protect
transforms.panTransform.ibm.key.protect.api.key=${IBM_KEY_PROTECT_API_KEY}
```

---

## Exemples Complets

### Configuration Locale Minimale

```properties
name=banking-s3-sink-local
connector.class=io.confluent.connect.s3.S3SinkConnector
tasks.max=1
topics=payments-in

s3.bucket.name=banking-payments
s3.region=us-east-1
store.url=http://minio:9000
aws.access.key.id=minioadmin
aws.secret.access.key=minioadmin

format.class=io.confluent.connect.s3.format.json.JsonFormat
rotate.schedule.interval.ms=300000
flush.size=1000

partitioner.class=com.banking.kafka.partitioner.BankingHierarchicalPartitioner
partitioner.institution.header=X-Institution-Id
partitioner.event.type.header=X-Event-Type
partitioner.event.version.header=X-Event-Version

transforms=headersToPayload,panTransform
transforms.headersToPayload.type=com.banking.kafka.transforms.HeadersToPayloadTransform
transforms.headersToPayload.mandatory.headers=X-Institution-Id,X-Event-Type,X-Event-Version

transforms.panTransform.type=com.banking.kafka.transforms.PANTransformationSMT
transforms.panTransform.strategy=REMOVE
```

### Configuration Production (IBM Cloud)

Voir [config/cloud/connector-ibm.properties](../config/cloud/connector-ibm.properties)
