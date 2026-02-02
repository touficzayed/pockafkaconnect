# Déploiement et Tests End-to-End

Ce guide explique comment déployer et tester le pipeline Banking Kafka Connect POC.

## Étape 1: Déployer le Connecteur Personnalisé

### 1.1 Copier le JAR dans Kafka Connect

```bash
# Copier le JAR dans le répertoire des connecteurs (nécessite sudo)
sudo mkdir -p docker/connectors/banking-custom
sudo cp target/kafka-connect-banking-poc-1.0.0-SNAPSHOT-uber.jar docker/connectors/banking-custom/
```

### 1.2 Redémarrer Kafka Connect

```bash
# Redémarrer le conteneur pour charger le nouveau connecteur
docker restart banking-kafka-connect

# Attendre que Kafka Connect soit prêt (environ 30 secondes)
sleep 30

# Vérifier le statut
docker logs banking-kafka-connect --tail 50
```

### 1.3 Vérifier que le Connecteur est Chargé

```bash
# Lister les plugins disponibles
curl -s http://localhost:8083/connector-plugins | jq '.[] | select(.class | contains("banking"))'
```

Vous devriez voir:
- `com.banking.kafka.transforms.HeadersToPayloadTransform`
- `com.banking.kafka.transforms.PANTransformationSMT`
- `com.banking.kafka.partitioner.BankingHierarchicalPartitioner`

## Étape 2: Créer le Connecteur S3 Sink

### 2.1 Déployer la Configuration

```bash
# Créer le connecteur S3 Sink avec nos transformations
curl -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d @config/connectors/s3-sink-connector.json

# Vérifier le statut du connecteur
curl -s http://localhost:8083/connectors/banking-s3-sink/status | jq .
```

### 2.2 Vérifier la Configuration

```bash
# Afficher la configuration complète
curl -s http://localhost:8083/connectors/banking-s3-sink | jq .
```

## Étape 3: Exécuter le Producer de Test

### 3.1 Compiler le Producer

```bash
# Le producer a déjà été compilé avec mvn package
# Vérifier que le JAR existe
ls -lh target/*.jar
```

### 3.2 Envoyer des Messages de Test

```bash
# Méthode 1: Utiliser Maven pour exécuter le producer
mvn exec:java \
  -Dexec.mainClass="com.banking.kafka.integration.BankingPaymentProducer" \
  -Dexec.args="localhost:9092 payments-in 20"

# Méthode 2: Exécuter directement avec Java
java -cp target/kafka-connect-banking-poc-1.0.0-SNAPSHOT-uber.jar \
  com.banking.kafka.integration.BankingPaymentProducer \
  localhost:9092 payments-in 20
```

Le producer enverra 20 messages de paiement avec:
- Différentes institutions (BNK001-BNK005)
- Headers Kafka (X-Institution-Id, X-Event-Type, etc.)
- PAN chiffré simulé
- Partitionnement basé sur l'institution ID

## Étape 4: Vérifier les Résultats

### 4.1 Vérifier les Messages dans Kafka

```bash
# Consommer les messages du topic
docker exec banking-kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic payments-in \
  --from-beginning \
  --max-messages 5 \
  --property print.headers=true
```

### 4.2 Vérifier les Fichiers dans MinIO

```bash
# Lister les fichiers dans le bucket
docker exec banking-minio-init mc ls minio/banking-payments --recursive

# Alternative: Via l'interface Web MinIO
# Ouvrir http://localhost:9001 dans le navigateur
# Login: minioadmin / minioadmin
```

### 4.3 Télécharger et Inspecter un Fichier

```bash
# Télécharger un fichier depuis MinIO
docker exec banking-minio-init mc cp \
  minio/banking-payments/year=2026/month=02/day=02/hour=13/payments-in+0+0000000000.json \
  /tmp/test-output.json

# Afficher le contenu
cat /tmp/test-output.json | jq .
```

Le fichier devrait contenir:
- ✅ Champ `metadata` avec les headers extraits (X-Institution-Id, X-Event-Type, etc.)
- ✅ Champ `encryptedPrimaryAccountNumber` **supprimé** (transformation REMOVE)
- ✅ Tous les autres champs du message original

### 4.4 Vérifier les Logs du Connecteur

```bash
# Afficher les logs de Kafka Connect
docker logs banking-kafka-connect --tail 100

# Vérifier les métriques du connecteur
curl -s http://localhost:8083/connectors/banking-s3-sink/status | jq .
```

## Étape 5: Tests Avancés

### 5.1 Tester la Transformation DECRYPT

Modifier la configuration du connecteur pour déchiffrer le PAN:

```bash
# Mettre à jour le connecteur
curl -X PUT http://localhost:8083/connectors/banking-s3-sink/config \
  -H "Content-Type: application/json" \
  -d '{
    "transforms.transformPAN.strategy": "DECRYPT",
    "transforms.transformPAN.source.field": "encryptedPrimaryAccountNumber",
    "transforms.transformPAN.target.field": "primaryAccountNumber",
    "transforms.transformPAN.private.key.path": "/keys/bank-private-key.pem"
  }'
```

**Note**: Nécessite une clé privée RSA valide montée dans le conteneur.

### 5.2 Tester la Transformation REKEY

Pour re-chiffrer avec la clé d'un partenaire:

```bash
curl -X PUT http://localhost:8083/connectors/banking-s3-sink/config \
  -H "Content-Type: application/json" \
  -d '{
    "transforms.transformPAN.strategy": "REKEY",
    "transforms.transformPAN.source.field": "encryptedPrimaryAccountNumber",
    "transforms.transformPAN.target.field": "encryptedPrimaryAccountNumber",
    "transforms.transformPAN.private.key.path": "/keys/bank-private-key.pem",
    "transforms.transformPAN.partner.keys.mapping.path": "/config/partner-keys.json",
    "transforms.transformPAN.institution.id.header": "X-Institution-Id"
  }'
```

### 5.3 Tester le Chiffrement PGP des Fichiers

```bash
# Générer une clé PGP de test
gpg --batch --gen-key <<EOF
Key-Type: RSA
Key-Length: 2048
Name-Real: Banking POC
Name-Email: banking@example.com
Expire-Date: 0
%no-protection
%commit
EOF

# Exporter la clé publique
gpg --armor --export banking@example.com > pgp-public-key.asc

# Chiffrer un fichier de test
java -cp target/kafka-connect-banking-poc-1.0.0-SNAPSHOT-uber.jar \
  com.banking.kafka.crypto.PGPFileEncryptor \
  encrypt \
  /tmp/test-output.json \
  /tmp/test-output.json.pgp \
  pgp-public-key.asc \
  --armor
```

## Étape 6: Nettoyage

### 6.1 Supprimer le Connecteur

```bash
# Arrêter et supprimer le connecteur
curl -X DELETE http://localhost:8083/connectors/banking-s3-sink
```

### 6.2 Vider le Topic Kafka

```bash
# Supprimer le topic
docker exec banking-kafka kafka-topics \
  --bootstrap-server localhost:9092 \
  --delete \
  --topic payments-in

# Recréer le topic
docker exec banking-kafka kafka-topics \
  --bootstrap-server localhost:9092 \
  --create \
  --topic payments-in \
  --partitions 3 \
  --replication-factor 1
```

### 6.3 Vider le Bucket MinIO

```bash
# Supprimer tous les fichiers
docker exec banking-minio-init mc rm --recursive --force minio/banking-payments/
```

## Dépannage

### Le Connecteur ne Démarre Pas

```bash
# Vérifier les logs
docker logs banking-kafka-connect

# Vérifier que le JAR est bien monté
docker exec banking-kafka-connect ls -lh /custom-connectors/

# Redémarrer Kafka Connect
docker restart banking-kafka-connect
```

### Erreurs de Transformation

```bash
# Activer les logs de débogage
curl -X PUT http://localhost:8083/admin/loggers/com.banking.kafka \
  -H "Content-Type: application/json" \
  -d '{"level": "DEBUG"}'

# Afficher les logs
docker logs banking-kafka-connect --tail 200 -f
```

### Aucun Fichier dans MinIO

```bash
# Vérifier que le connecteur est en cours d'exécution
curl -s http://localhost:8083/connectors/banking-s3-sink/status

# Vérifier les offsets
docker exec banking-kafka kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --describe \
  --group banking-connect-group

# Augmenter flush.size pour forcer l'écriture
curl -X PUT http://localhost:8083/connectors/banking-s3-sink/config \
  -H "Content-Type: application/json" \
  -d '{"flush.size": "1"}'
```

## Métriques et Monitoring

```bash
# Afficher les métriques du connecteur
curl -s http://localhost:8083/connectors/banking-s3-sink/status | jq '.tasks[].trace'

# Vérifier les offsets commités
docker exec banking-kafka kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --describe \
  --group connect-banking-s3-sink
```

## Succès Attendus

À la fin des tests, vous devriez avoir:

✅ Connecteur S3 Sink en statut RUNNING
✅ 20 messages envoyés au topic `payments-in`
✅ Fichiers JSONL dans MinIO avec structure de répertoires par date
✅ Headers Kafka extraits dans le champ `metadata` des fichiers
✅ Champ PAN chiffré supprimé des fichiers
✅ Partitionnement cohérent par institution ID
✅ Logs sans erreurs dans Kafka Connect

## Phase Suivante

Une fois les tests end-to-end validés, vous pouvez passer à:
- **Phase 7**: Déploiement sur IBM Cloud avec IBM Cloud Object Storage
