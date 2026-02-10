#!/bin/bash
# ====================================================================
# Generate Test Cryptographic Keys for Banking POC
# ====================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
KEYS_DIR="$PROJECT_ROOT/config/local/keys"

echo "=========================================="
echo "Banking POC - Test Keys Generation"
echo "=========================================="
echo ""

# Create directory structure
mkdir -p "$KEYS_DIR/my-institution"
mkdir -p "$KEYS_DIR/partners"
mkdir -p "$KEYS_DIR/pgp"

echo "✓ Created directory structure"
echo ""

# ====================================================================
# 1. Generate RSA keys for our institution (to decrypt incoming PANs)
# ====================================================================
echo "[1/3] Generating RSA keys for MY INSTITUTION..."

if [ -f "$KEYS_DIR/my-institution/private-key.pem" ]; then
    echo "⚠️  private-key.pem already exists. Skipping..."
else
    openssl genpkey -algorithm RSA \
        -out "$KEYS_DIR/my-institution/private-key.pem" \
        -pkeyopt rsa_keygen_bits:2048

    openssl rsa -pubout \
        -in "$KEYS_DIR/my-institution/private-key.pem" \
        -out "$KEYS_DIR/my-institution/public-key.pem"

    echo "my-key-2026-01" > "$KEYS_DIR/my-institution/key-id.txt"

    echo "✓ Generated RSA key pair for my-institution"
fi

echo ""

# ====================================================================
# 2. Generate RSA public keys for partner banks (for REKEY mode)
# ====================================================================
echo "[2/3] Generating RSA public keys for PARTNER BANKS..."

for bank in bnk001 bnk002 bnk003; do
    if [ -f "$KEYS_DIR/partners/${bank}-public.pem" ]; then
        echo "⚠️  ${bank}-public.pem already exists. Skipping..."
    else
        # In reality, partner banks would provide their public keys
        # For testing, we generate a full pair and keep only the public key

        temp_private="/tmp/${bank}-private-temp.pem"

        openssl genpkey -algorithm RSA \
            -out "$temp_private" \
            -pkeyopt rsa_keygen_bits:2048 2>/dev/null

        openssl rsa -pubout \
            -in "$temp_private" \
            -out "$KEYS_DIR/partners/${bank}-public.pem" 2>/dev/null

        # Clean up temp private key
        rm -f "$temp_private"

        echo "✓ Generated public key for $bank"
    fi
done

echo ""

# ====================================================================
# 3. Generate PGP keys for file encryption
# ====================================================================
echo "[3/3] Generating PGP keys for FILE ENCRYPTION..."

if [ -f "$KEYS_DIR/pgp/recipient-public.asc" ]; then
    echo "⚠️  PGP keys already exist. Skipping..."
else
    # Check if gpg is available
    if ! command -v gpg &> /dev/null; then
        echo "⚠️  GPG not found. Skipping PGP key generation."
        echo "   Install GPG to enable PGP encryption: apt-get install gnupg"
    else
        # Generate PGP key pair
        gpg --batch --gen-key <<EOF 2>/dev/null
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

        # Export public key
        gpg --armor --export test@banking-poc.local > "$KEYS_DIR/pgp/recipient-public.asc" 2>/dev/null

        # Export private key (for test decryption only)
        gpg --armor --export-secret-keys test@banking-poc.local > "$KEYS_DIR/pgp/recipient-private.asc" 2>/dev/null

        # Save passphrase for reference
        echo "TestPassword123" > "$KEYS_DIR/pgp/passphrase.txt"

        # Create per-bank symlinks to the generic public key
        for bank in bnk001 bnk002 bnk003 bnk004 bnk005 hsbc bnpp socgen; do
            ln -sf recipient-public.asc "$KEYS_DIR/pgp/${bank}-public.asc"
        done

        echo "✓ Generated PGP key pair"
        echo "  Email: test@banking-poc.local"
        echo "  Passphrase: TestPassword123 (saved in passphrase.txt)"
        echo "  Per-bank symlinks created for: bnk001-bnk005, hsbc, bnpp, socgen"
    fi
fi

echo ""
echo "=========================================="
echo "✓ Key Generation Complete!"
echo "=========================================="
echo ""
echo "Generated keys:"
echo "  • RSA (My Institution): $KEYS_DIR/my-institution/"
echo "  • RSA (Partners):       $KEYS_DIR/partners/"
echo "  • PGP:                  $KEYS_DIR/pgp/"
echo ""
echo "⚠️  SECURITY REMINDER:"
echo "  • These keys are for DEVELOPMENT/TESTING only"
echo "  • NEVER commit private keys to Git"
echo "  • In production, use IBM Key Protect"
echo ""

# Set appropriate permissions
chmod 600 "$KEYS_DIR/my-institution/private-key.pem" 2>/dev/null || true
chmod 600 "$KEYS_DIR/pgp/recipient-private.asc" 2>/dev/null || true
chmod 600 "$KEYS_DIR/pgp/passphrase.txt" 2>/dev/null || true

echo "✓ Set restrictive permissions on private keys"
echo ""
