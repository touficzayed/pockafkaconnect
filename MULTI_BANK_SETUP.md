# Configuration Multi-Banques

Ce guide explique comment configurer le pipeline pour gérer plusieurs banques avec des stratégies différentes.

## Scénarios par Banque

### BNK001 - Banque Nationale
**Stratégie PAN**: REMOVE (suppression)
**Chiffrement PGP**: ✅ Activé (ASCII armor)
**Cas d'usage**: Conformité stricte PCI-DSS - ne stocke AUCUN PAN

```json
{
  "transactionId": "TXN-001",
  "amount": 150.00,
  "encryptedPrimaryAccountNumber": "eyJhbGc..."
}
```

**Après transformation**:
```json
{
  "transactionId": "TXN-001",
  "amount": 150.00,
  "metadata": {
    "X-Institution-Id": "BNK001",
    "X-Event-Type": "PAYMENT"
  }
  // ❌ PAN complètement supprimé
}
```

**Fichier stocké**: Chiffré avec PGP (format ASCII armor)

---

### BNK002 - Crédit Populaire
**Stratégie PAN**: DECRYPT (déchiffrement)
**Chiffrement PGP**: ❌ Désactivé
**Cas d'usage**: Système legacy nécessitant le PAN en clair

```json
{
  "transactionId": "TXN-002",
  "amount": 250.00,
  "encryptedPrimaryAccountNumber": "eyJhbGc..."
}
```

**Après transformation**:
```json
{
  "transactionId": "TXN-002",
  "amount": 250.00,
  "primaryAccountNumber": "4532123456789012",  // ✅ PAN déchiffré
  "metadata": {
    "X-Institution-Id": "BNK002",
    "X-Event-Type": "PAYMENT"
  }
}
```

**Fichier stocké**: Non chiffré PGP (uniquement chiffrement S3)

---

### BNK003 - Banque Internationale
**Stratégie PAN**: REKEY (re-chiffrement)
**Chiffrement PGP**: ✅ Activé (binaire)
**Cas d'usage**: Re-chiffrement avec clé propre pour isolation

```json
{
  "transactionId": "TXN-003",
  "amount": 500.00,
  "encryptedPrimaryAccountNumber": "eyJhbGc..."  // Chiffré avec clé centrale
}
```

**Après transformation**:
```json
{
  "transactionId": "TXN-003",
  "amount": 500.00,
  "encryptedPrimaryAccountNumber": "eyJhbGc...",  // ✅ Re-chiffré avec clé BNK003
  "metadata": {
    "X-Institution-Id": "BNK003",
    "X-Event-Type": "PAYMENT"
  }
}
```

**Fichier stocké**: Chiffré avec PGP (format binaire, plus compact)

---

### BNK004 - Banque Régionale
**Stratégie PAN**: NONE (aucune)
**Chiffrement PGP**: ✅ Activé (ASCII armor)
**Cas d'usage**: N'envoie jamais de PAN dans les messages

```json
{
  "transactionId": "TXN-004",
  "amount": 75.00,
  "accountToken": "TOKEN-1234"  // ❌ Pas de champ PAN
}
```

**Après transformation**:
```json
{
  "transactionId": "TXN-004",
  "amount": 75.00,
  "accountToken": "TOKEN-1234",
  "metadata": {
    "X-Institution-Id": "BNK004",
    "X-Event-Type": "PAYMENT"
  }
}
```

**Fichier stocké**: Chiffré avec PGP pour conformité réglementaire

---

### BNK005 - Banque Digitale
**Stratégie PAN**: DECRYPT + Tokenization
**Chiffrement PGP**: ✅ Activé (ASCII armor)
**Cas d'usage**: Double chiffrement pour sécurité maximale (PCI-DSS Level 1)

```json
{
  "transactionId": "TXN-005",
  "amount": 1000.00,
  "encryptedPrimaryAccountNumber": "eyJhbGc..."
}
```

**Après transformation**:
```json
{
  "transactionId": "TXN-005",
  "amount": 1000.00,
  "tokenizedPAN": "TOK-453212******9012",  // ✅ PAN tokenisé
  "metadata": {
    "X-Institution-Id": "BNK005",
    "X-Event-Type": "PAYMENT"
  }
}
```

**Fichier stocké**: Double chiffrement (PGP + S3)

---

## Configuration du Connecteur

### Option 1: Configuration Globale (Un connecteur pour toutes les banques) — Recommandée

```json
{
  "name": "banking-s3-sink-multibank",
  "config": {
    "connector.class": "io.confluent.connect.s3.S3SinkConnector",
    "tasks.max": "20",
    "topics": "payments-in",

    "transforms": "extractHeaders,transformPANPerBank",

    "transforms.extractHeaders.type": "com.banking.kafka.transforms.HeadersToPayloadTransform",
    "transforms.extractHeaders.mandatory.headers": "X-Institution-Id,X-Event-Type",
    "transforms.extractHeaders.target.field": "metadata",

    "transforms.transformPANPerBank.type": "com.banking.kafka.transforms.PANTransformationSMT",
    "transforms.transformPANPerBank.bank.config.path": "/config/banks/bank-config.json",
    "transforms.transformPANPerBank.institution.id.header": "X-Institution-Id",

    "s3.bucket.name": "banking-payments",
    "partitioner.class": "com.banking.kafka.partitioner.BankingHierarchicalPartitioner",
    "partitioner.bank.partition.mapping.file": "/config/banks/bank-partition-mapping.csv"
  }
}
```

**Partitioning**: Le partitionneur supporte deux modes:
- **Mapping CSV déterministe** via `bank-partition-mapping.csv` — contrôle total de la distribution
- **Murmur2 hashing** (fallback pour les banques non listées dans le CSV)

**Scaling**: Avec 20 partitions Kafka et 20 tasks, chaque task gère ~10 banques.
Le chiffrement PGP est en streaming (via `PGPOutputStreamWrapper`), sans buffering mémoire.
```

### Option 2: Configuration par Banque (Un connecteur par banque)

#### Connecteur BNK001
```json
{
  "name": "banking-s3-sink-bnk001",
  "config": {
    "connector.class": "io.confluent.connect.s3.S3SinkConnector",
    "topics": "payments-in",

    "transforms": "extractHeaders,transformPAN",

    "transforms.extractHeaders.type": "com.banking.kafka.transforms.HeadersToPayloadTransform",
    "transforms.extractHeaders.mandatory.headers": "X-Institution-Id",

    "transforms.transformPAN.type": "com.banking.kafka.transforms.PANTransformationSMT",
    "transforms.transformPAN.strategy": "REMOVE",
    "transforms.transformPAN.source.field": "encryptedPrimaryAccountNumber",

    "s3.bucket.name": "banking-payments",
    "s3.object.key.template": "bnk001/year={{yyyy}}/month={{MM}}/day={{dd}}/{{topic}}-{{partition}}-{{start_offset}}.json"
  }
}
```

#### Connecteur BNK002
```json
{
  "name": "banking-s3-sink-bnk002",
  "config": {
    "connector.class": "io.confluent.connect.s3.S3SinkConnector",
    "topics": "payments-in",

    "transforms": "extractHeaders,transformPAN",

    "transforms.transformPAN.type": "com.banking.kafka.transforms.PANTransformationSMT",
    "transforms.transformPAN.strategy": "DECRYPT",
    "transforms.transformPAN.source.field": "encryptedPrimaryAccountNumber",
    "transforms.transformPAN.target.field": "primaryAccountNumber",
    "transforms.transformPAN.private.key.path": "/keys/bank-private-key.pem",

    "s3.bucket.name": "banking-payments",
    "s3.object.key.template": "bnk002/year={{yyyy}}/month={{MM}}/day={{dd}}/{{topic}}-{{partition}}-{{start_offset}}.json"
  }
}
```

---

## Génération des Clés PGP

### Script de Génération

```bash
#!/bin/bash
# Générer les clés PGP pour toutes les banques

BANKS=("bnk001" "bnk002" "bnk003" "bnk004" "bnk005")

for bank in "${BANKS[@]}"; do
  echo "Generating PGP keys for $bank..."

  gpg --batch --gen-key <<EOF
Key-Type: RSA
Key-Length: 2048
Name-Real: Banking POC ${bank^^}
Name-Email: ${bank}@banking-poc.local
Expire-Date: 0
%no-protection
%commit
EOF

  # Export public key
  gpg --armor --export "${bank}@banking-poc.local" > "config/local/keys/pgp/${bank}-public.asc"

  # Export private key
  gpg --armor --export-secret-keys "${bank}@banking-poc.local" > "config/local/keys/pgp/${bank}-private.asc"

  echo "✅ Keys generated for $bank"
done
```

---

## Producer de Test Multi-Banques

```java
// Envoyer des messages pour différentes banques

// BNK001: Avec PAN chiffré (sera supprimé)
sendPayment("BNK001", "TXN-001", 150.00, "eyJhbGc...");

// BNK002: Avec PAN chiffré (sera déchiffré)
sendPayment("BNK002", "TXN-002", 250.00, "eyJhbGc...");

// BNK003: Avec PAN chiffré (sera re-chiffré)
sendPayment("BNK003", "TXN-003", 500.00, "eyJhbGc...");

// BNK004: Sans PAN (pas de transformation)
Map<String, Object> payment = new HashMap<>();
payment.put("transactionId", "TXN-004");
payment.put("amount", 75.00);
payment.put("accountToken", "TOKEN-1234");
sendPaymentWithoutPAN("BNK004", payment);

// BNK005: Avec PAN chiffré (sera déchiffré et tokenisé)
sendPayment("BNK005", "TXN-005", 1000.00, "eyJhbGc...");
```

---

## Vérification des Résultats

### 1. Vérifier les Fichiers dans S3/MinIO

```bash
# BNK001: Fichiers chiffrés PGP (ASCII)
docker exec banking-minio-init mc cat minio/banking-payments/bnk001/...
# Output: -----BEGIN PGP MESSAGE-----

# BNK002: Fichiers non chiffrés avec PAN en clair
docker exec banking-minio-init mc cat minio/banking-payments/bnk002/...
# Output: {"primaryAccountNumber":"4532..."}

# BNK003: Fichiers chiffrés PGP (binaire)
docker exec banking-minio-init mc cat minio/banking-payments/bnk003/...
# Output: Binary PGP data

# BNK004: Fichiers chiffrés PGP sans PAN
docker exec banking-minio-init mc cat minio/banking-payments/bnk004/...
# Output: -----BEGIN PGP MESSAGE-----

# BNK005: Fichiers doublement chiffrés avec PAN tokenisé
docker exec banking-minio-init mc cat minio/banking-payments/bnk005/...
# Output: -----BEGIN PGP MESSAGE-----
```

### 2. Déchiffrer un Fichier PGP

```bash
# Télécharger un fichier
docker exec banking-minio-init mc cp \
  minio/banking-payments/bnk001/.../file.json \
  /tmp/encrypted.json

# Déchiffrer avec GPG
gpg --decrypt /tmp/encrypted.json > /tmp/decrypted.json

# Voir le contenu
cat /tmp/decrypted.json | jq .
```

---

## Tableau Récapitulatif

| Banque | Stratégie PAN | PGP | Cas d'Usage |
|--------|---------------|-----|-------------|
| BNK001 | REMOVE | ✅ ASCII | Conformité stricte |
| BNK002 | DECRYPT | ❌ Non | Système legacy |
| BNK003 | REKEY | ✅ Binaire | Isolation |
| BNK004 | NONE | ✅ ASCII | Pas de PAN |
| BNK005 | DECRYPT+Token | ✅ ASCII | Sécurité max |

---

## Mapping des Partitions (Scaling à 200+ banques)

### Fichier `config/banks/bank-partition-mapping.csv`

```csv
# bankCode,partitionNumber
# 20 partitions total, ~10 banques par partition
BNK001,0
BNK002,1
BNK003,2
BNK004,3
BNK005,4
# Étendre à 200 banques avec:
# for i in $(seq 1 200); do printf "BNK%03d,%d\n" $i $(( (i-1) % 20 )); done
```

Les banques non listées tombent en fallback sur le hachage Murmur2 (même algo que le `DefaultPartitioner` Kafka).

### Profil mémoire

| Config | Banques/task | Fichiers ouverts/task | Mémoire buffers/task |
|--------|-------------|----------------------|---------------------|
| 20 tasks, 200 banques | ~10 | ~60 | ~150 MB |
| 10 tasks, 200 banques | ~20 | ~120 | ~300 MB |
| 3 tasks, 200 banques | ~67 | ~400 | ~1 GB |

Le chiffrement PGP streaming (`PGPOutputStreamWrapper`) ajoute seulement ~8 KB par fichier ouvert.

---

## Tests de Charge

```bash
# Envoyer 1000 messages pour chaque banque
for bank in BNK001 BNK002 BNK003 BNK004 BNK005; do
  mvn exec:java \
    -Dexec.mainClass="com.banking.kafka.integration.BankingPaymentProducer" \
    -Dexec.args="localhost:9092 payments-in 1000 $bank"
done
```

---

## Monitoring par Banque

```bash
# Compter les fichiers par banque
for bank in bnk001 bnk002 bnk003 bnk004 bnk005; do
  count=$(docker exec banking-minio-init mc find minio/banking-payments/$bank --name "*.json" | wc -l)
  echo "$bank: $count fichiers"
done
```
