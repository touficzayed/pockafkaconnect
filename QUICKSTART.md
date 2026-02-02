# Quick Start Guide

Guide de démarrage rapide pour le POC Banking Kafka Connect.

## Prérequis

Assurez-vous d'avoir installé:
- ✅ Docker & Docker Compose
- ✅ Java 11+ (pour le développement)
- ✅ Maven 3.6+ (pour le build)
- ✅ Git

Vérification:
```bash
docker --version
docker-compose --version
java -version
mvn -version
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
  --partitions 3 \
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
├── pom.xml                    ← Configuration Maven
│
├── docs/                      ← Documentation technique
│   ├── architecture.md        ← Design complet du système
│   ├── configuration.md       ← Tous les paramètres expliqués
│   └── next-steps.md          ← Plan d'implémentation détaillé
│
├── src/main/java/             ← Code source (à implémenter)
│   └── com/banking/kafka/
│       ├── transforms/        ← Single Message Transforms
│       ├── partitioner/       ← Custom partitioner
│       └── crypto/            ← JWE/PGP handlers
│
├── config/                    ← Configurations
│   ├── local/                 ← Environnement local
│   │   ├── connector.properties
│   │   ├── partner-keys-mapping.json
│   │   └── keys/              ← Clés de chiffrement (gitignored)
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

## Prochaines Étapes

### Option A: Comprendre l'Architecture

Lisez la documentation dans cet ordre:

1. [README.md](README.md) - Vue d'ensemble
2. [docs/architecture.md](docs/architecture.md) - Design détaillé
3. [docs/configuration.md](docs/configuration.md) - Paramètres
4. [docs/next-steps.md](docs/next-steps.md) - Plan d'implémentation

### Option B: Commencer l'Implémentation

Suivez le plan d'implémentation dans [docs/next-steps.md](docs/next-steps.md):

**Phase 2: Implémenter HeadersToPayloadTransform**

Cette transformation extrait les headers Kafka et les ajoute au payload JSON.

Fichier à créer: `src/main/java/com/banking/kafka/transforms/HeadersToPayloadTransform.java`

Je peux vous guider dans l'implémentation Java!

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

L'infrastructure est en place. Vous pouvez maintenant:

1. **Explorer** l'architecture et la documentation
2. **Implémenter** les composants Java (voir [docs/next-steps.md](docs/next-steps.md))
3. **Tester** le flow complet avec l'environnement local

**Besoin d'aide pour implémenter?** Dites-moi par quelle phase commencer!
