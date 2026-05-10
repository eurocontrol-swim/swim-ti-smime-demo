#!/bin/bash
# Generate test X.509 certificates for SWIM S/MIME demo
# Producer cert: used for signing (integrity)
# Consumer cert: used for encryption (confidentiality)

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# Prevent Git Bash MSYS path conversion
export MSYS_NO_PATHCONV=1

echo "=== Generating SWIM S/MIME Demo Certificates ==="

# Clean up previous certs
rm -f *.pem *.pfx *.key *.crt *.srl *.csr *.cnf

# --- Root CA (self-signed) ---
echo "[1/5] Creating Root CA..."
openssl req -x509 -newkey rsa:2048 -keyout ca.key -out ca.crt -days 365 -nodes \
  -subj "/C=EU/O=SWIM Demo/CN=SWIM Demo CA" 2>/dev/null

# --- Producer certificate (for signing) ---
echo "[2/5] Creating Producer certificate (signing)..."
openssl req -newkey rsa:2048 -keyout producer.key -out producer.csr -nodes \
  -subj "/C=EU/O=SWIM Demo/CN=SWIM Producer" 2>/dev/null

printf "keyUsage=digitalSignature\nextendedKeyUsage=emailProtection\n" > producer_ext.cnf
openssl x509 -req -in producer.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -out producer.crt -days 365 -extfile producer_ext.cnf 2>/dev/null
rm -f producer_ext.cnf

# --- Consumer certificate (for encryption) ---
echo "[3/5] Creating Consumer certificate (encryption)..."
openssl req -newkey rsa:2048 -keyout consumer.key -out consumer.csr -nodes \
  -subj "/C=EU/O=SWIM Demo/CN=SWIM Consumer" 2>/dev/null

printf "keyUsage=keyEncipherment\nextendedKeyUsage=emailProtection\n" > consumer_ext.cnf
openssl x509 -req -in consumer.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -out consumer.crt -days 365 -extfile consumer_ext.cnf 2>/dev/null
rm -f consumer_ext.cnf

# --- Export to PKCS#12 (.pfx) for Java and C# ---
echo "[4/5] Exporting PKCS#12 bundles..."
openssl pkcs12 -export -in producer.crt -inkey producer.key -certfile ca.crt \
  -out producer.pfx -password pass:changeit -name producer 2>/dev/null

openssl pkcs12 -export -in consumer.crt -inkey consumer.key -certfile ca.crt \
  -out consumer.pfx -password pass:changeit -name consumer 2>/dev/null

# --- Combined PEM files (cert + key) for Python ---
echo "[5/5] Creating combined PEM files..."
cat producer.crt producer.key > producer-combined.pem
cat consumer.crt consumer.key > consumer-combined.pem

# Cleanup CSRs
rm -f *.csr *.srl

echo ""
echo "=== Certificates Generated ==="
echo "  CA:       ca.crt"
echo "  Producer: producer.crt, producer.key, producer.pfx (pass: changeit)"
echo "  Consumer: consumer.crt, consumer.key, consumer.pfx (pass: changeit)"
echo ""
echo "Producer cert is used for SIGNING (integrity)"
echo "Consumer cert is used for ENCRYPTION (confidentiality)"
