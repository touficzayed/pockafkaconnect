# Prochaines Étapes - Implémentation du POC

Ce document décrit les prochaines étapes pour implémenter le POC Banking Kafka Connect.

## État Actuel

✅ **Phases 1-7: Implémentation Core + Scaling (TERMINÉ)**

- ✅ **Phase 1**: Setup environnement (Maven, Docker Compose, Kafka, MinIO)
- ✅ **Phase 2**: HeadersToPayloadTransform (7 tests passants)
- ✅ **Phase 3**: PANTransformationSMT (3 tests passants) - REMOVE, DECRYPT, REKEY
- ✅ **Phase 4**: BankingHierarchicalPartitioner (18 tests passants)
- ✅ **Phase 5**: PGP Encryption avec BouncyCastle (17 tests passants)
- ✅ **Phase 6**: Configuration Multi-Banques
  - BankConfigManager pour configuration centralisée
  - Configuration JSON par banque (5 banques: BNK001-BNK005)
  - BankPGPEncryptor pour chiffrement PGP par banque
  - MultiBankPaymentProducer pour tests multi-banques
  - Documentation complète (MULTI_BANK_SETUP.md)
- ✅ **Phase 7**: Scaling et Streaming PGP
  - Partitionneur Murmur2 + mapping CSV déterministe (remplace String.hashCode())
  - PGPOutputStreamWrapper pour chiffrement streaming (zéro buffering mémoire)
  - Configuration 20 partitions / 20 tasks pour 200+ banques
  - Tests de distribution 200 banques sur 20 partitions

**Total: 45 tests unitaires passants**

**Fonctionnalités clés implémentées:**
- Transformation PAN avec stratégies par banque (REMOVE/DECRYPT/REKEY/NONE)
- Chiffrement PGP streaming via `PGPOutputStreamWrapper` (zéro buffering mémoire)
- Partitioning déterministe (CSV) ou Murmur2 — scalable à 200+ banques
- 20 tasks parallèles (~10 banques/task)
- Gestion centralisée des configurations multi-banques
- Support de 5 scénarios bancaires couvrant tous les cas d'usage

---

## Plan d'Implémentation

### Phase 1: Setup de l'Environnement ✅ (TERMINÉ)

**Objectif**: Valider que l'infrastructure fonctionne avant de coder.

**Actions:**
1. Démarrer l'environnement Docker
   ```bash
   cd docker
   docker-compose up -d
   ```

2. Générer les clés de test
   ```bash
   ./scripts/generate-test-keys.sh
   ```

3. Vérifier les services
   - Kafka: http://localhost:9092
   - Kafka Connect: http://localhost:8083
   - MinIO Console: http://localhost:9001

4. Créer un topic de test
   ```bash
   docker exec -it banking-kafka kafka-topics --create \
     --topic payments-in \
     --bootstrap-server localhost:9092 \
     --partitions 3 \
     --replication-factor 1
   ```

**Validation:**
- Tous les services démarrent sans erreur
- Kafka Connect REST API répond
- MinIO est accessible et le bucket `banking-payments` existe

---

### Phase 2: SMT HeadersToPayloadTransform ✅ (TERMINÉ)

**Objectif**: Extraire les headers Kafka et les ajouter au payload.

**Fichiers à créer:**
```
src/main/java/com/banking/kafka/transforms/
└── HeadersToPayloadTransform.java
```

**Tests à créer:**
```
src/test/java/com/banking/kafka/transforms/
└── HeadersToPayloadTransformTest.java
```

**Étapes d'implémentation:**

1. **Créer la classe de base**
   - Étendre `org.apache.kafka.connect.transforms.Transformation<SinkRecord>`
   - Implémenter les méthodes `apply()`, `config()`, `close()`

2. **Définir la configuration**
   - ConfigDef avec:
     - `mandatory.headers` (String, required)
     - `optional.headers` (String, default="")
     - `target.field` (String, default="headers")
     - `fail.on.missing.mandatory` (Boolean, default=true)

3. **Implémenter la logique**
   - Lire les headers du SinkRecord
   - Valider les headers obligatoires
   - Créer un objet JSON avec les headers
   - Wrapper le payload existant
   - Retourner un nouveau SinkRecord

4. **Tests unitaires**
   - Test avec tous les headers présents
   - Test avec headers obligatoires manquants
   - Test avec headers optionnels manquants
   - Test avec headers invalides

**Validation:**
```bash
mvn test -Dtest=HeadersToPayloadTransformTest
```

---

### Phase 3: SMT PANTransformationSMT ✅ (TERMINÉ)

**Objectif**: Gérer la transformation du PAN chiffré (REMOVE, DECRYPT, REKEY).

**Fichiers à créer:**
```
src/main/java/com/banking/kafka/
├── transforms/
│   └── PANTransformationSMT.java
└── crypto/
    ├── JWEHandler.java
    ├── KeyStorageProvider.java
    ├── FileKeyStorageProvider.java
    └── IBMKeyProtectProvider.java (Phase 7)
```

**Tests à créer:**
```
src/test/java/com/banking/kafka/
├── transforms/
│   └── PANTransformationSMTTest.java
└── crypto/
    ├── JWEHandlerTest.java
    └── KeyStorageProviderTest.java
```

**Étapes d'implémentation:**

#### 3.1. Mode REMOVE (le plus simple en premier)

1. Créer la classe `PANTransformationSMT`
2. Configurer la stratégie `REMOVE`
3. Supprimer le champ `source.field` du payload
4. Tests unitaires pour REMOVE

#### 3.2. JWEHandler (pour DECRYPT et REKEY)

1. Créer `JWEHandler` avec Nimbus JOSE+JWT
   - Méthode `decrypt(String jwe, RSAPrivateKey key)`
   - Méthode `encrypt(String plaintext, RSAPublicKey key)`

2. Tests:
   - Générer un JWE de test
   - Déchiffrer avec la clé privée
   - Re-chiffrer avec une clé publique

#### 3.3. KeyStorageProvider

1. Interface `KeyStorageProvider`
   ```java
   interface KeyStorageProvider {
       RSAPrivateKey getPrivateKey(String keyId);
       RSAPublicKey getPublicKey(String keyId);
   }
   ```

2. Implémentation `FileKeyStorageProvider`
   - Charger les clés depuis le filesystem
   - Caching des clés en mémoire

3. Tests avec des clés de test

#### 3.4. Mode DECRYPT

1. Intégrer JWEHandler dans PANTransformationSMT
2. Déchiffrer le PAN
3. Remplacer dans le target field
4. Tests E2E avec JWE réel

#### 3.5. Mode REKEY

1. Charger le mapping des clés partenaires
2. Déchiffrer avec notre clé privée
3. Re-chiffrer avec la clé publique du partenaire
4. Tests avec plusieurs institutions

**Validation:**
```bash
mvn test -Dtest=PANTransformationSMTTest
```

---

### Phase 4: Custom Partitioner ✅ (TERMINÉ)

**Objectif**: Partitioning hiérarchique institution/event-type/version/date.

**Fichiers à créer:**
```
src/main/java/com/banking/kafka/partitioner/
└── BankingHierarchicalPartitioner.java
```

**Tests à créer:**
```
src/test/java/com/banking/kafka/partitioner/
└── BankingHierarchicalPartitionerTest.java
```

**Étapes d'implémentation:**

1. Étendre `io.confluent.connect.storage.partitioner.Partitioner`
2. Lire les headers configurés (institution, event-type, version)
3. Construire le chemin: `{institution}/{event-type}/{version}/year={YYYY}/month={MM}/day={DD}/hour={HH}/`
4. Gérer les valeurs par défaut si headers manquants
5. Tests avec différentes combinaisons de headers

**Validation:**
```bash
mvn test -Dtest=BankingHierarchicalPartitionerTest
```

---

### Phase 5: PGP Encryption ✅ (TERMINÉ)

**Objectif**: Chiffrer les fichiers en streaming avec PGP.

**Fichiers à créer:**
```
src/main/java/com/banking/kafka/crypto/
├── PGPEncryptionWrapper.java
└── PGPStreamingOutputStream.java
```

**Étapes d'implémentation:**

1. Wrapper autour du S3 OutputStream
2. Utiliser BouncyCastle pour PGP
3. Streaming encryption (pas de buffering du fichier entier)
4. Tests avec clés PGP de test

**Validation:**
- Générer un fichier chiffré
- Vérifier qu'il est déchiffrable avec GPG

---

### Phase 6: Configuration Multi-Banques ✅ (TERMINÉ)

**Objectif**: Permettre des configurations différentes par banque (stratégie PAN + PGP).

**Fichiers créés:**
```
src/main/java/com/banking/kafka/
├── config/
│   └── BankConfigManager.java
├── crypto/
│   └── BankPGPEncryptor.java
└── test/java/com/banking/kafka/integration/
    └── MultiBankPaymentProducer.java

config/banks/
└── bank-config.json

docs/
└── MULTI_BANK_SETUP.md
```

**Implémentation réalisée:**

1. **BankConfigManager**
   - Charge la configuration JSON centralisée
   - Cache les configurations par banque
   - Fournit une configuration par défaut en fallback

2. **BankPGPEncryptor**
   - Chiffrement PGP spécifique par banque
   - Cache des clés publiques par banque
   - Support ASCII armor et binaire selon la banque

3. **MultiBankPaymentProducer**
   - Producteur de test pour 5 banques
   - Couvre tous les scénarios (REMOVE, DECRYPT, REKEY, NONE, DECRYPT+Token)
   - Peut tester toutes les banques ou une banque spécifique

**5 Scénarios bancaires implémentés:**

| Banque | Stratégie PAN | PGP | Format PGP | Use Case |
|--------|---------------|-----|------------|----------|
| BNK001 | REMOVE | ✅ | ASCII | Conformité stricte PCI-DSS |
| BNK002 | DECRYPT | ❌ | - | Système legacy nécessitant PAN clair |
| BNK003 | REKEY | ✅ | Binaire | Isolation avec clé propre |
| BNK004 | NONE | ✅ | ASCII | Banque utilisant tokens uniquement |
| BNK005 | DECRYPT+Token | ✅ | ASCII | Sécurité maximale (double chiffrement) |

**Validation:**
```bash
# Tester toutes les banques (10 messages par banque)
java -jar target/kafka-connect-banking-poc-*.jar \
  com.banking.kafka.integration.MultiBankPaymentProducer \
  localhost:9092 payments-in 10

# Tester une banque spécifique (50 messages)
java -jar target/kafka-connect-banking-poc-*.jar \
  com.banking.kafka.integration.MultiBankPaymentProducer \
  localhost:9092 payments-in 50 BNK002
```

**Documentation:**
- Guide complet: `MULTI_BANK_SETUP.md`
- Configuration examples pour chaque banque
- Vérification des résultats dans MinIO/S3
- Tests de charge multi-banques

---

### Phase 7: Tests E2E 🧪

**Objectif**: Tester le flow complet avec l'environnement Docker.

**Actions:**

1. **Builder le connector**
   ```bash
   mvn clean package
   cp target/kafka-connect-banking-poc-1.0.0-SNAPSHOT-uber.jar docker/connectors/
   ```

2. **Redémarrer Kafka Connect**
   ```bash
   docker-compose restart kafka-connect
   ```

3. **Déployer le connector**
   ```bash
   curl -X POST http://localhost:8083/connectors \
     -H "Content-Type: application/json" \
     -d @config/local/connector.json
   ```

4. **Créer un producer de test**
   - Script Python/Java qui génère des messages
   - Avec headers Kafka appropriés
   - Avec PAN chiffré en JWE

5. **Vérifier les fichiers dans MinIO**
   - Ouvrir http://localhost:9001
   - Naviguer dans `banking-payments`
   - Vérifier la structure de partitioning
   - Télécharger un fichier et valider le format JSONL

6. **Tests de charge**
   - Envoyer 10,000 messages
   - Vérifier la rotation des fichiers
   - Vérifier les métriques

**Scénarios de test:**

| Scénario | Institution | Event Type | Strategy | Attendu |
|----------|-------------|------------|----------|---------|
| 1 | BNK001 | PAYMENT | REMOVE | PAN supprimé |
| 2 | BNK002 | PAYMENT | DECRYPT | PAN en clair |
| 3 | BNK003 | REFUND | REKEY | PAN re-chiffré avec clé BNK003 |
| 4 | BNK004 | PAYMENT | NONE | Pas de PAN dans le message |
| 5 | BNK005 | PAYMENT | DECRYPT | PAN tokenisé |
| 6 | UNKNOWN | PAYMENT | - | Utiliser config default |
| 7 | BNK001 | (manquant) | - | → DLQ |

**Tests Multi-Banques:**
```bash
# Envoyer des messages pour toutes les banques
mvn exec:java \
  -Dexec.mainClass="com.banking.kafka.integration.MultiBankPaymentProducer" \
  -Dexec.args="localhost:9092 payments-in 100"

# Vérifier les fichiers dans MinIO par banque
for bank in bnk001 bnk002 bnk003 bnk004 bnk005; do
  echo "=== $bank ==="
  docker exec banking-minio-init mc find minio/banking-payments/$bank --name "*.json*"
done
```

---

### Phase 8: Cloud Deployment (IBM) ☁️ ⏳ (À VENIR)

**Objectif**: Déployer sur IBM Cloud avec Event Streams + COS + Key Protect.

**Prérequis:**
- Compte IBM Cloud
- IBM Event Streams instance
- IBM Cloud Object Storage instance
- IBM Key Protect instance

**Actions:**

1. **Créer les ressources IBM Cloud**
   ```bash
   # Event Streams
   ibmcloud resource service-instance-create banking-event-streams \
     messagehub standard us-south

   # COS
   ibmcloud resource service-instance-create banking-cos \
     cloud-object-storage standard global

   # Key Protect
   ibmcloud resource service-instance-create banking-key-protect \
     kms tiered-pricing us-south
   ```

2. **Configurer Key Protect**
   - Importer les clés RSA
   - Créer les policies IAM
   - Tester l'API

3. **Implémenter IBMKeyProtectProvider**
   ```java
   src/main/java/com/banking/kafka/crypto/
   └── IBMKeyProtectProvider.java
   ```

4. **Builder l'image Docker**
   ```dockerfile
   FROM confluentinc/cp-kafka-connect:7.6.0
   COPY target/*.jar /usr/share/java/kafka-connect-banking/
   ```

5. **Déployer sur Kubernetes (IKS)**
   - Créer les ConfigMaps
   - Créer les Secrets
   - Déployer via Helm ou kubectl

6. **Configurer le monitoring**
   - IBM Cloud Monitoring (Sysdig)
   - LogDNA pour les logs
   - Alertes sur les erreurs

---

## Checklist Globale

### Phase 1: Setup ✅
- [x] Docker Compose up
- [x] Générer les clés de test
- [x] Vérifier tous les services
- [x] Créer le topic Kafka

### Phase 2: HeadersToPayloadTransform ✅
- [x] Implémenter la classe
- [x] Ajouter tests unitaires (15 tests)
- [x] Valider avec Maven

### Phase 3: PANTransformationSMT ✅
- [x] Mode REMOVE
- [x] JWEHandler
- [x] KeyStorageProvider (FILE)
- [x] Mode DECRYPT
- [x] Mode REKEY
- [x] Tests unitaires (12 tests)

### Phase 4: Partitioner ✅
- [x] Implémenter BankingHierarchicalPartitioner (Murmur2 + CSV mapping)
- [x] Tests unitaires (18 tests, dont distribution 200 banques/20 partitions)
- [x] Valider les chemins générés

### Phase 5: PGP ✅
- [x] PGPEncryptionHandler avec BouncyCastle
- [x] Tests avec génération/déchiffrement clés

### Phase 6: Configuration Multi-Banques ✅
- [x] BankConfigManager (configuration centralisée)
- [x] Configuration JSON (5 banques)
- [x] BankPGPEncryptor (chiffrement par banque)
- [x] MultiBankPaymentProducer (tests multi-banques)
- [x] Documentation (MULTI_BANK_SETUP.md)

### Phase 7: Tests E2E ⏳
- [x] Builder le connector (uber JAR)
- [x] Producer de test multi-banques
- [ ] Déployer localement avec Docker Compose
- [ ] Validation MinIO (fichiers par banque)
- [ ] Tests de charge (1000+ messages)

### Phase 8: Cloud ⏳
- [ ] Créer les ressources IBM Cloud
- [ ] IBMKeyProtectProvider
- [ ] Docker image pour Kubernetes
- [ ] Déploiement IKS/OpenShift
- [ ] Monitoring (Sysdig, LogDNA)

---

## Commandes Utiles

### Maven
```bash
# Compiler
mvn compile

# Tests unitaires
mvn test

# Package
mvn clean package

# Tests d'intégration
mvn verify -P integration-tests
```

### Docker
```bash
# Démarrer
./scripts/start-local-env.sh

# Logs
docker-compose -f docker/docker-compose.yml logs -f kafka-connect

# Arrêter
docker-compose -f docker/docker-compose.yml down
```

### Kafka
```bash
# Créer topic
docker exec banking-kafka kafka-topics --create \
  --topic payments-in --bootstrap-server localhost:9092 \
  --partitions 3 --replication-factor 1

# Lister topics
docker exec banking-kafka kafka-topics --list \
  --bootstrap-server localhost:9092

# Consommer
docker exec banking-kafka kafka-console-consumer \
  --topic payments-in --bootstrap-server localhost:9092 \
  --from-beginning
```

### Kafka Connect
```bash
# Lister connectors
curl http://localhost:8083/connectors

# Status
curl http://localhost:8083/connectors/banking-s3-sink/status

# Supprimer
curl -X DELETE http://localhost:8083/connectors/banking-s3-sink
```

---

## Questions / Décisions à Prendre

1. **Performance**: Quel throughput cible? (msgs/sec)
2. **Sécurité**: Audit des déchiffrements de PAN?
3. **Monitoring**: Métriques custom à exposer?
4. **Erreurs**: Comportement si la clé partenaire n'existe pas?
5. **Cloud**: Quelle région IBM Cloud?

---

## État et Prochaines Étapes

### ✅ Réalisé (Phases 1-6)

**31 tests unitaires passants**

Le POC est fonctionnel avec:
- Transformation PAN avec 4 stratégies (REMOVE/DECRYPT/REKEY/NONE)
- Chiffrement PGP optionnel et configurable par banque
- Configuration multi-banques centralisée (JSON)
- Partitioning hiérarchique
- Producer de test multi-banques

### 🚧 En Cours (Phase 7: Tests E2E)

**Actions à réaliser:**

1. **Déploiement local complet**
   ```bash
   # Démarrer l'environnement
   cd docker
   docker-compose up -d

   # Copier le connector JAR
   cp target/kafka-connect-banking-poc-*.jar connectors/

   # Déployer le connector
   curl -X POST http://localhost:8083/connectors \
     -H "Content-Type: application/json" \
     -d @config/local/connector-multibank.json
   ```

2. **Tests avec producer multi-banques**
   ```bash
   # Envoyer 100 messages pour chaque banque
   java -jar target/kafka-connect-banking-poc-*.jar \
     com.banking.kafka.integration.MultiBankPaymentProducer \
     localhost:9092 payments-in 100
   ```

3. **Validation des fichiers dans MinIO**
   - Vérifier la structure par banque (bnk001/, bnk002/, etc.)
   - Vérifier les fichiers PGP (extension .pgp pour banques avec PGP)
   - Déchiffrer et valider le contenu

4. **Tests de charge**
   - Envoyer 10,000+ messages
   - Mesurer le throughput
   - Vérifier la rotation des fichiers

### ⏳ À Venir (Phase 8: Cloud Deployment)

**Déploiement sur IBM Cloud:**
- IBM Event Streams (Kafka managé)
- IBM Cloud Object Storage (COS)
- IBM Key Protect pour gestion des clés
- Kubernetes (IKS/OpenShift)
- Monitoring avec Sysdig

### Options Actuelles

**Option A: Compléter les Tests E2E**
```bash
# Démarrer tout l'environnement local
./scripts/start-local-env.sh
```

**Option B: Ajouter de Nouvelles Banques**
- Modifier `config/banks/bank-config.json`
- Ajouter les clés PGP correspondantes
- Tester avec le producer multi-banques

**Option C: Préparer le Cloud Deployment**
- Implémenter `IBMKeyProtectProvider`
- Créer le Dockerfile pour Kubernetes
- Préparer les Helm charts

**Option D: Améliorer les Fonctionnalités**
- Ajouter la tokenisation du PAN (BNK005)
- Implémenter des métriques custom
- Ajouter le support Avro/Parquet

**Quelle option souhaitez-vous poursuivre?**
