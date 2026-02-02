# Quick Start Guide

Guide de démarrage rapide pour le POC Banking Kafka Connect.

## Prérequis

Assurez-vous d'avoir installé:
- ✅ Docker & Docker Compose
- ✅ Java 11+ (pour le développement et l'exécution)
- ✅ Maven 3.6+ (pour le build)
- ✅ Git
- ✅ GPG (pour générer les clés PGP multi-banques)

Vérification:
```bash
docker --version
docker-compose --version
java -version
mvn -version
gpg --version
```

## Démarrage en 5 Minutes

### 1. Ouvrir le Projet dans VSCode

Le projet est déjà configuré avec toutes les extensions et settings VSCode recommandées.

```bash
cd kafka-connect-banking-poc
code .
```

VSCode vous proposera d'installer les extensions Java recommandées. Acceptez.

### 2. Générer les Clés de Test

```bash
./scripts/generate-test-keys.sh
```

Cela crée:
- Clé RSA pour notre institution (déchiffrement)
- Clés publiques des banques partenaires (re-chiffrement)
- Clés PGP pour le chiffrement des fichiers

### 3. Démarrer l'Environnement Local

```bash
./scripts/start-local-env.sh
```

Cela démarre:
- Zookeeper
- Kafka (broker)
- Kafka Connect
- MinIO (S3-compatible)

Attendez que tous les services soient prêts (~30 secondes).

### 4. Vérifier les Services

Ouvrez dans votre navigateur:

| Service | URL | Credentials |
|---------|-----|-------------|
| MinIO Console | http://localhost:9001 | user: minioadmin<br>password: minioadmin |
| Kafka Connect API | http://localhost:8083 | - |

**Test rapide:**
```bash
# Vérifier Kafka Connect
curl http://localhost:8083/

# Lister les connectors (vide pour l'instant)
curl http://localhost:8083/connectors
```

### 5. Créer le Topic Kafka

```bash
docker exec banking-kafka kafka-topics --create \
  --topic payments-in \
  --bootstrap-server localhost:9092 \
  --partitions 20 \
  --replication-factor 1
```

Vérification:
```bash
docker exec banking-kafka kafka-topics --list \
  --bootstrap-server localhost:9092
```

Vous devriez voir `payments-in` dans la liste.

## Structure du Projet

```
kafka-connect-banking-poc/
├── README.md                  ← Vue d'ensemble du projet
├── QUICKSTART.md              ← Ce fichier
├── MULTI_BANK_SETUP.md        ← Guide configuration multi-banques
├── pom.xml                    ← Configuration Maven
│
├── docs/                      ← Documentation technique
│   ├── architecture.md        ← Design complet du système
│   ├── configuration.md       ← Tous les paramètres expliqués
│   └── next-steps.md          ← Plan d'implémentation détaillé
│
├── src/main/java/             ← Code source (✅ IMPLÉMENTÉ)
│   └── com/banking/kafka/
│       ├── transforms/        ← Single Message Transforms
│       │   ├── HeadersToPayloadTransform.java
│       │   ├── PANTransformationSMT.java
│       │   └── JSONLFormatTransform.java
│       ├── partitioner/       ← Custom partitioner (Murmur2 + CSV mapping)
│       │   └── BankingHierarchicalPartitioner.java
│       ├── config/            ← Configuration multi-banques
│       │   └── BankConfigManager.java
│       └── crypto/            ← JWE/PGP handlers
│           ├── JWEHandler.java
│           ├── PGPEncryptionHandler.java
│           ├── PGPOutputStreamWrapper.java  ← Streaming PGP (zéro buffering)
│           ├── BankPGPEncryptor.java
│           └── KeyStorageProvider.java
│
├── src/test/java/             ← Tests (45 tests passants ✅)
│   └── com/banking/kafka/
│       ├── transforms/        ← Tests SMT
│       ├── partitioner/       ← Tests partitioner
│       ├── crypto/            ← Tests crypto
│       └── integration/       ← Producers de test
│           └── MultiBankPaymentProducer.java
│
├── config/                    ← Configurations
│   ├── banks/                 ← Configuration multi-banques
│   │   ├── bank-config.json   ← Config JSON (BNK001-BNK005)
│   │   └── bank-partition-mapping.csv  ← Mapping banque→partition
│   ├── local/                 ← Environnement local
│   │   ├── connector.properties
│   │   ├── partner-keys-mapping.json
│   │   └── keys/              ← Clés de chiffrement
│   │       ├── pgp/           ← Clés PGP par banque
│   │       └── ...
│   └── cloud/                 ← Environnement IBM Cloud
│       └── connector-ibm.properties
│
├── docker/                    ← Infrastructure locale
│   └── docker-compose.yml
│
└── scripts/                   ← Scripts d'automatisation
    ├── generate-test-keys.sh
    └── start-local-env.sh
```

## Tests Multi-Banques (Phases 1-7 Complétées ✅)

Le POC est entièrement fonctionnel avec **45 tests passants**. Vous pouvez maintenant tester les différents scénarios bancaires.

### Compiler et Packager

```bash
mvn clean package
```

Le JAR sera généré dans `target/kafka-connect-banking-poc-1.0-SNAPSHOT-jar-with-dependencies.jar`

### Tester Toutes les Banques

Envoyer 10 messages pour chaque banque (BNK001-BNK005):

```bash
java -jar target/kafka-connect-banking-poc-*-jar-with-dependencies.jar \
  com.banking.kafka.integration.MultiBankPaymentProducer \
  localhost:9092 payments-in 10
```

### Tester Une Banque Spécifique

Envoyer 50 messages pour BNK002:

```bash
java -jar target/kafka-connect-banking-poc-*-jar-with-dependencies.jar \
  com.banking.kafka.integration.MultiBankPaymentProducer \
  localhost:9092 payments-in 50 BNK002
```

### Scénarios Bancaires

| Banque | Stratégie PAN | PGP | Format | Use Case |
|--------|---------------|-----|--------|----------|
| BNK001 | REMOVE | ✅ | ASCII | Conformité stricte PCI-DSS |
| BNK002 | DECRYPT | ❌ | - | Système legacy nécessitant PAN clair |
| BNK003 | REKEY | ✅ | Binaire | Isolation avec clé propre |
| BNK004 | NONE | ✅ | ASCII | Banque utilisant tokens uniquement |
| BNK005 | DECRYPT+Token | ✅ | ASCII | Sécurité maximale |

### Vérifier les Résultats

**Voir les fichiers par banque dans MinIO:**

```bash
# BNK001 - Fichiers chiffrés PGP (ASCII armor)
docker exec banking-minio-init mc cat minio/banking-payments/bnk001/.../*.json

# BNK002 - Fichiers non chiffrés avec PAN en clair
docker exec banking-minio-init mc cat minio/banking-payments/bnk002/.../*.json

# Compter les fichiers par banque
for bank in bnk001 bnk002 bnk003 bnk004 bnk005; do
  count=$(docker exec banking-minio-init mc find minio/banking-payments/$bank --name "*.json*" | wc -l)
  echo "$bank: $count fichiers"
done
```

## Prochaines Étapes

### Option A: Comprendre l'Architecture Multi-Banques

Lisez la documentation dans cet ordre:

1. [README.md](README.md) - Vue d'ensemble
2. [MULTI_BANK_SETUP.md](MULTI_BANK_SETUP.md) - Configuration multi-banques
3. [docs/architecture.md](docs/architecture.md) - Design détaillé
4. [docs/configuration.md](docs/configuration.md) - Paramètres
5. [docs/next-steps.md](docs/next-steps.md) - Plan E2E et Cloud

### Option B: Tests E2E avec Kafka Connect

Déployer le connector et tester le flow complet:

```bash
# Copier le JAR vers les connectors
cp target/kafka-connect-banking-poc-*.jar docker/connectors/

# Redémarrer Kafka Connect
docker-compose -f docker/docker-compose.yml restart kafka-connect

# Déployer le connector multi-banques
curl -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d @config/local/connector-multibank.json
```

### Option C: Explorer l'Environnement

Explorez les services démarrés:

#### MinIO (Object Storage)
1. Ouvrir http://localhost:9001
2. Login: minioadmin / minioadmin
3. Naviguer dans le bucket `banking-payments`

#### Kafka Connect
```bash
# API REST
curl http://localhost:8083/connector-plugins | jq

# Logs
docker-compose -f docker/docker-compose.yml logs -f kafka-connect
```

#### Kafka
```bash
# Producer de test
docker exec -it banking-kafka kafka-console-producer \
  --topic payments-in \
  --bootstrap-server localhost:9092

# Consumer de test
docker exec -it banking-kafka kafka-console-consumer \
  --topic payments-in \
  --bootstrap-server localhost:9092 \
  --from-beginning
```

## Commandes VSCode

Utilisez les tâches VSCode configurées:

1. **Ctrl+Shift+P** → "Tasks: Run Task"
2. Choisir:
   - `Maven: Compile` - Compiler le code
   - `Maven: Test` - Lancer les tests
   - `Maven: Package` - Builder le JAR
   - `Docker: Start Environment` - Démarrer Docker
   - `Generate Test Keys` - Générer les clés

Ou via le terminal VSCode:
- **Ctrl+Shift+`** pour ouvrir un terminal intégré

## Tests

### Compiler le Projet
```bash
mvn compile
```

### Lancer les Tests Unitaires
```bash
mvn test
```

### Packager le Connector
```bash
mvn clean package
```

Le JAR sera dans `target/kafka-connect-banking-poc-1.0.0-SNAPSHOT-uber.jar`

## Troubleshooting

### Docker ne démarre pas
```bash
# Vérifier que Docker est lancé
docker info

# Si erreur, redémarrer Docker Desktop
```

### Port déjà utilisé
```bash
# Vérifier les ports occupés
lsof -i :9092  # Kafka
lsof -i :8083  # Kafka Connect
lsof -i :9000  # MinIO

# Arrêter les containers existants
docker-compose -f docker/docker-compose.yml down
```

### Services ne démarrent pas
```bash
# Voir les logs
docker-compose -f docker/docker-compose.yml logs

# Redémarrer un service spécifique
docker-compose -f docker/docker-compose.yml restart kafka-connect
```

### Maven erreur de dépendances
```bash
# Nettoyer le cache Maven
mvn clean
rm -rf ~/.m2/repository/com/banking/kafka

# Re-télécharger les dépendances
mvn dependency:resolve
```

## Arrêter l'Environnement

```bash
cd docker
docker-compose down

# Ou avec suppression des volumes
docker-compose down -v
```

## Support

Pour toute question sur:
- **Architecture générale**: Voir [docs/architecture.md](docs/architecture.md)
- **Configuration**: Voir [docs/configuration.md](docs/configuration.md)
- **Implémentation**: Voir [docs/next-steps.md](docs/next-steps.md)

## Références Rapides

- [Kafka Connect API](https://kafka.apache.org/documentation/#connect)
- [Confluent S3 Sink](https://docs.confluent.io/kafka-connect-s3-sink/current/)
- [Nimbus JOSE+JWT (JWE)](https://connect2id.com/products/nimbus-jose-jwt)
- [BouncyCastle (PGP)](https://www.bouncycastle.org/)
- [IBM Key Protect](https://cloud.ibm.com/docs/key-protect)

---

## Vous êtes prêt! 🚀

Le POC est **entièrement implémenté et testé**. Vous pouvez maintenant:

1. **Explorer** l'architecture multi-banques dans la documentation
2. **Tester** les 5 scénarios bancaires avec le producer de test
3. **Déployer** le connector en local avec Kafka Connect
4. **Valider** les fichiers générés dans MinIO/S3
5. **Préparer** le déploiement cloud (IBM Event Streams + COS)

**État du projet:**
- ✅ 45 tests unitaires passants
- ✅ 5 scénarios bancaires implémentés (BNK001-BNK005)
- ✅ Configuration multi-banques centralisée
- ✅ Chiffrement PGP streaming (zéro buffering mémoire)
- ✅ Partitioning déterministe (Murmur2 + CSV mapping, 20 partitions/tasks)
- ✅ Producer de test multi-banques
- ⏳ Tests E2E avec Kafka Connect (prêt à déployer)
- ⏳ Déploiement cloud IBM (à venir)

**Pour aller plus loin:**
- Voir [MULTI_BANK_SETUP.md](MULTI_BANK_SETUP.md) pour les détails de configuration
- Voir [docs/next-steps.md](docs/next-steps.md) pour les tests E2E et cloud deployment
