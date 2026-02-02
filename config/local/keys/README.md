# Clés de Chiffrement - Environnement Local

## Avertissement de Sécurité

⚠️ **IMPORTANT**: Ce dossier contient des clés cryptographiques sensibles.
- **JAMAIS** committer de clés privées dans Git
- Les clés de ce dossier sont pour **DEV/TEST UNIQUEMENT**
- En production, utiliser IBM Key Protect

## Structure Attendue

```
keys/
├── my-institution/
│   ├── private-key.pem          # Clé privée RSA pour déchiffrer les PANs
│   ├── public-key.pem           # Clé publique correspondante
│   └── key-id.txt               # Key ID (ex: my-key-2026-01)
├── partners/
│   ├── bnk001-public.pem        # Clé publique Banque 001 (pour REKEY)
│   ├── bnk002-public.pem        # Clé publique Banque 002
│   └── bnk003-public.pem        # Clé publique Banque 003
└── pgp/
    ├── recipient-public.asc     # Clé PGP publique pour chiffrer les fichiers
    └── recipient-private.asc    # Clé PGP privée (pour tests de déchiffrement)
```

## Génération des Clés de Test

### 1. Clés RSA pour JWE (My Institution)

```bash
# Générer une paire de clés RSA 2048 bits
openssl genpkey -algorithm RSA -out my-institution/private-key.pem -pkeyopt rsa_keygen_bits:2048

# Extraire la clé publique
openssl rsa -pubout -in my-institution/private-key.pem -out my-institution/public-key.pem

# Créer le Key ID
echo "my-key-2026-01" > my-institution/key-id.txt

# Vérifier les clés
openssl rsa -in my-institution/private-key.pem -text -noout
```

### 2. Clés RSA pour Banques Partenaires (REKEY)

```bash
# Pour chaque banque partenaire, on a seulement besoin de leur clé publique
# En réalité, ces clés vous seraient fournies par les banques partenaires

# Génération de clés de test:
for bank in bnk001 bnk002 bnk003; do
    # Générer une paire (privée pour la banque, on garde seulement la publique)
    openssl genpkey -algorithm RSA -out partners/${bank}-private-temp.pem -pkeyopt rsa_keygen_bits:2048
    openssl rsa -pubout -in partners/${bank}-private-temp.pem -out partners/${bank}-public.pem
    rm partners/${bank}-private-temp.pem  # On supprime la privée (elle appartient à la banque)
done
```

### 3. Clés PGP pour Chiffrement des Fichiers

```bash
# Générer une paire de clés PGP de test
gpg --batch --gen-key <<EOF
Key-Type: RSA
Key-Length: 2048
Subkey-Type: RSA
Subkey-Length: 2048
Name-Real: Banking POC Test
Name-Email: test@banking-poc.local
Expire-Date: 1y
Passphrase: TestPassword123
%commit
EOF

# Exporter la clé publique
gpg --armor --export test@banking-poc.local > pgp/recipient-public.asc

# Exporter la clé privée (pour tests seulement)
gpg --armor --export-secret-keys test@banking-poc.local > pgp/recipient-private.asc
```

## Script de Génération Automatique

Un script est fourni pour générer toutes les clés de test:

```bash
cd /home/toufic/kafka-connect-banking-poc
./scripts/generate-test-keys.sh
```

## Vérification des Clés

### Vérifier une clé RSA privée
```bash
openssl rsa -in my-institution/private-key.pem -check -noout
```

### Vérifier une clé RSA publique
```bash
openssl rsa -pubin -in partners/bnk001-public.pem -text -noout
```

### Vérifier une clé PGP
```bash
gpg --show-keys pgp/recipient-public.asc
```

## Test de Chiffrement/Déchiffrement

### Test JWE/RSA

```bash
# Créer un message de test
echo "1234567890123456" > test-pan.txt

# Chiffrer avec la clé publique (utiliser nimbus-jose-jwt ou un script)
# Voir scripts/test-jwe-encryption.sh

# Déchiffrer avec la clé privée
# Voir scripts/test-jwe-decryption.sh
```

### Test PGP

```bash
# Chiffrer un fichier
gpg --encrypt --recipient test@banking-poc.local --output test.txt.pgp test.txt

# Déchiffrer
gpg --decrypt --output test-decrypted.txt test.txt.pgp
```

## Format des Clés

### RSA PEM (PKCS#8)
```
-----BEGIN PRIVATE KEY-----
MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQC...
-----END PRIVATE KEY-----
```

### RSA Public Key
```
-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA...
-----END PUBLIC KEY-----
```

### PGP ASCII Armor
```
-----BEGIN PGP PUBLIC KEY BLOCK-----

mQENBGXXXXXBCACxxxxxxxxxxxxxxxxxxxxxxx...
-----END PGP PUBLIC KEY BLOCK-----
```

## Rotation des Clés

En production (IBM Key Protect):
- Rotation automatique tous les 90 jours
- Support de multiple key versions
- Audit trail complet

En local (pour dev):
- Régénérer les clés manuellement avec le script
- Mettre à jour le key-id dans la configuration

## Troubleshooting

### Erreur "unable to load Private Key"
```bash
# Vérifier le format de la clé
file my-institution/private-key.pem
# Doit retourner: "PEM RSA private key"

# Convertir si nécessaire
openssl rsa -in old-key.pem -out private-key.pem
```

### Erreur "Unsupported key format"
```bash
# Convertir PKCS#1 vers PKCS#8
openssl pkcs8 -topk8 -inform PEM -outform PEM -nocrypt \
  -in pkcs1-key.pem -out private-key.pem
```

## Références

- [OpenSSL Documentation](https://www.openssl.org/docs/)
- [JWE Specification (RFC 7516)](https://tools.ietf.org/html/rfc7516)
- [PGP/GPG Tutorial](https://www.gnupg.org/gph/en/manual.html)
- [IBM Key Protect Best Practices](https://cloud.ibm.com/docs/key-protect?topic=key-protect-security-and-compliance)
