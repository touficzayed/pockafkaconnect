# Prochaines Étapes - Implémentation du POC

Ce document décrit les prochaines étapes pour implémenter le POC Banking Kafka Connect.

## État Actuel

✅ **Phase 0: Setup et Design (TERMINÉ)**
- Structure du projet créée
- Documentation architecture complète
- Configuration templates (local + cloud)
- Docker Compose environment
- Scripts d'automatisation
- Configuration VSCode

---

## Plan d'Implémentation

### Phase 1: Setup de l'Environnement ⏳

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

### Phase 2: SMT HeadersToPayloadTransform 📝

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

### Phase 3: SMT PANTransformationSMT 🔐

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

### Phase 4: Custom Partitioner 📂

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

### Phase 5: PGP Encryption (Optionnel) 🔒

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

### Phase 6: Tests E2E 🧪

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
| 2 | BNK001 | PAYMENT | DECRYPT | PAN en clair |
| 3 | BNK002 | REFUND | REKEY | PAN re-chiffré avec clé BNK002 |
| 4 | UNKNOWN | PAYMENT | REMOVE | Utiliser defaults |
| 5 | BNK001 | (manquant) | - | → DLQ |

---

### Phase 7: Cloud Deployment (IBM) ☁️

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

### Phase 1: Setup ⏳
- [ ] Docker Compose up
- [ ] Générer les clés de test
- [ ] Vérifier tous les services
- [ ] Créer le topic Kafka

### Phase 2: HeadersToPayloadTransform
- [ ] Implémenter la classe
- [ ] Ajouter tests unitaires
- [ ] Valider avec Maven

### Phase 3: PANTransformationSMT
- [ ] Mode REMOVE
- [ ] JWEHandler
- [ ] KeyStorageProvider (FILE)
- [ ] Mode DECRYPT
- [ ] Mode REKEY
- [ ] Tests E2E

### Phase 4: Partitioner
- [ ] Implémenter BankingHierarchicalPartitioner
- [ ] Tests unitaires
- [ ] Valider les chemins générés

### Phase 5: PGP (Optionnel)
- [ ] PGPEncryptionWrapper
- [ ] Tests avec GPG

### Phase 6: Tests E2E
- [ ] Builder le connector
- [ ] Déployer localement
- [ ] Producer de test
- [ ] Validation MinIO
- [ ] Tests de charge

### Phase 7: Cloud
- [ ] Créer les ressources IBM
- [ ] IBMKeyProtectProvider
- [ ] Docker image
- [ ] Déploiement Kubernetes
- [ ] Monitoring

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

## Prêt à Commencer?

La structure et la documentation sont maintenant complètes. Vous pouvez:

1. **Option A**: Commencer par la Phase 1 (Setup environnement)
   ```bash
   cd kafka-connect-banking-poc
   ./scripts/start-local-env.sh
   ```

2. **Option B**: Commencer par la Phase 2 (Implémenter HeadersToPayloadTransform)
   - Je peux vous guider dans l'implémentation Java

3. **Option C**: Approfondir un aspect spécifique
   - Architecture JWE
   - Intégration IBM Key Protect
   - Tests E2E

**Quelle phase souhaitez-vous attaquer en premier?**
