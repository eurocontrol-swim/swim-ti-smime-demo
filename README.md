# SWIM S/MIME 4.0 Demo

Working example of S/MIME 4.0 (RFC 8551) message security for the [EUROCONTROL SWIM TI Yellow Profile](https://www.eurocontrol.int/publication/eurocontrol-spec-170-eurocontrol-specification-swim-technical-infrastructure-ti-yellow) specification (SWIM-TIYP-0112), demonstrating cross-language interoperability across **Java**, **Python**, and **C#** for both **REST** and **AMQP 1.0** transports.

## What This Demonstrates

The Yellow Profile Edition 2.0 introduces two interface bindings that mandate S/MIME 4.0:

- **WS Light with Message Security** (REST/HTTP + S/MIME)
- **AMQP Messaging with Message Security** (AMQP 1.0 + S/MIME)

This project shows how to implement both bindings with producers and consumers written in different languages, proving interoperability:

| # | Scenario | Producer | Consumer | Transport | Security |
|---|---|---|---|---|---|
| 1 | REST Sign | Java (Spring Boot) | Python (Flask) | HTTP | Integrity |
| 2 | REST Sign+Encrypt | Python | C# (ASP.NET) | HTTP | Integrity + Confidentiality |
| 3 | AMQP Sign | C# (AMQPNetLite) | Java (Qpid ProtonJ2) | AMQP 1.0 | Integrity |
| 4 | AMQP Sign+Encrypt | Java (Qpid ProtonJ2) | Python (qpid-proton) | AMQP 1.0 | Integrity + Confidentiality |

The payload is a FIXM-inspired JSON flight data message.

## Prerequisites

| Software | Version | Install (examples for Windows 11)|
|---|---|---|
| Java JDK | 17 LTS (Eclipse Temurin) | `winget install EclipseAdoptium.Temurin.17.JDK` |
| Python | 3.12 | `winget install Python.Python.3.12` |
| .NET SDK | 9.0 | `winget install Microsoft.DotNet.SDK.9` |
| Apache Maven | 3.9+ | [maven.apache.org](https://maven.apache.org/download.cgi) |
| ActiveMQ Artemis | 2.x (AMQP scenarios only) | [activemq.apache.org](https://activemq.apache.org/components/artemis/download/) |
| Git for Windows | Latest (includes OpenSSL and Git Bash) | [git-scm.com](https://git-scm.com) |

## Quick Start

### 1. Generate Test Certificates

```bash
cd certs
bash generate-certs.sh
```

This creates a demo CA, a producer certificate (for signing), and a consumer certificate (for encryption).

### 2. Install Python Dependencies

```bash
pip install -r python/requirements.txt
```

### 3. Build Java Module

```bash
cd java
mvn compile
```

### 4. Set Up AMQP Broker (for AMQP scenarios)

Create and start an Artemis broker instance:

```bash
# Create instance (one-time)
<artemis-install>/bin/artemis create broker/swim-broker \
    --user admin --password admin --queues swim.flight.data

# Start
broker/swim-broker/bin/artemis run
```

### 5. Run All Tests

See [Automated Test Script](#automated-test-script) below for full details. Quick version:

```bash
bash run-tests.sh           # All scenarios (broker must be running)
bash run-tests.sh --no-amqp # REST scenarios only
```

## Project Structure

```
swim-smime-demo/
+-- certs/                         # Test X.509 certificates
|   +-- generate-certs.sh          # Certificate generation script
+-- payload/
|   +-- sample-flight.json         # FIXM-inspired JSON payload
+-- java/                          # Java module
|   +-- pom.xml                    # Maven project (Spring Boot, Bouncy Castle, ProtonJ2)
|   +-- src/main/java/com/swim/smime/
|       +-- SmimeHelper.java       # S/MIME sign/verify/encrypt/decrypt
|       +-- RestProducer.java      # HTTP POST S/MIME messages
|       +-- RestConsumer.java      # Spring Boot server receiving S/MIME
|       +-- AmqpProducer.java      # AMQP 1.0 producer (ProtonJ2)
|       +-- AmqpConsumer.java      # AMQP 1.0 consumer (ProtonJ2)
+-- python/                        # Python module
|   +-- requirements.txt           # cryptography, asn1crypto, flask, qpid-proton
|   +-- smime_helper.py            # S/MIME sign/verify/encrypt/decrypt
|   +-- rest_consumer.py           # Flask server receiving S/MIME
|   +-- rest_producer.py           # HTTP POST S/MIME messages
|   +-- amqp_consumer.py           # AMQP 1.0 consumer (qpid-proton)
|   +-- amqp_producer.py           # AMQP 1.0 producer (qpid-proton)
+-- csharp/                        # C# module
|   +-- csharp.csproj              # .NET 9 project (ASP.NET, AMQPNetLite)
|   +-- SmimeHelper.cs             # S/MIME sign/verify/encrypt/decrypt
|   +-- Program.cs                 # REST consumer/producer + AMQP producer/consumer
+-- broker/                        # AMQP broker
|   +-- setup-artemis.ps1          # Artemis setup script
+-- run-tests.sh                   # Automated test runner
+-- docs/                          # Documentation
```

## S/MIME Approach

### Opaque Signatures (not multipart/signed)

This project uses **opaque signatures** (`application/pkcs7-mime; smime-type=signed-data`) where the payload is embedded inside the CMS structure. This was chosen over `multipart/signed` (detached signatures) because:

- Eliminates MIME boundary parsing inconsistencies across languages
- Naturally satisfies SWIM-TIYP-0112's "single data section" requirement
- Simpler to handle in AMQP (no multipart structure in a single data section)

### Content Types

| Operation | Content-Type |
|---|---|
| Sign only | `application/pkcs7-mime; smime-type=signed-data; name="smime.p7m"` |
| Encrypt (or sign+encrypt) | `application/pkcs7-mime; smime-type=enveloped-data; name="smime.p7m"` |

### Message Flow

**Sign only**: `payload -> CMS_Sign -> base64 -> transport`

**Sign + Encrypt**: `payload -> CMS_Sign -> CMS_Encrypt -> base64 -> transport`

**Decrypt + Verify**: `base64 -> CMS_Decrypt -> CMS_Verify -> payload`

### SWIM-TIYP-0112 Headers

**REST (HTTP)**:
- `Content-Type`: the S/MIME content type
- `MIME-Version: 1.0`

**AMQP**:
- `properties.content-type` (core property): the S/MIME content type
- Application property `MIME-Version`: `"1.0"`

## Libraries Used

### S/MIME / CMS

| Language | Library | Key Classes / Functions |
|---|---|---|
| **Java** | Bouncy Castle `bcpkix-jdk18on` 1.78.1 | `CMSSignedDataGenerator`, `CMSEnvelopedDataGenerator`, `CMSAlgorithm.AES256_CBC` |
| **Python** | `cryptography` (PyCA) + `asn1crypto` | `PKCS7SignatureBuilder`, `PKCS7EnvelopeBuilder`, `pkcs7_decrypt_der`, `cms.ContentInfo` |
| **C#** | `System.Security.Cryptography.Pkcs` (built-in) | `SignedCms`, `EnvelopedCms`, `CmsSigner` |

### AMQP 1.0

| Language | Library | Why not JMS? |
|---|---|---|
| **Java** | Apache Qpid ProtonJ2 (`protonj2-client`) | JMS restricts property names to Java identifiers (no hyphens), so `MIME-Version` cannot be set as required by SWIM-TIYP-0112 |
| **C#** | AMQPNetLite | Native AMQP 1.0 |
| **Python** | qpid-proton | Native AMQP 1.0 |

## Running Individual Scenarios

### Scenario 1: Java -> Python (REST, Sign Only)

```bash
# Terminal 1
cd python && python rest_consumer.py

# Terminal 2
cd java && mvn exec:java -Dexec.mainClass="com.swim.smime.RestProducer" \
  -Dexec.args="../certs ../payload/sample-flight.json http://localhost:5000/receive sign"
```

### Scenario 2: Python -> C# (REST, Sign+Encrypt)

```bash
# Terminal 1
cd csharp && dotnet run -- rest-consumer ../certs 8443

# Terminal 2
cd python && python rest_producer.py --url http://localhost:8443/receive --mode sign-encrypt
```

### Scenario 3: C# -> Java (AMQP, Sign Only)

```bash
# Terminal 0: Start broker
broker/swim-broker/bin/artemis run

# Terminal 1
cd java && mvn exec:java -Dexec.mainClass="com.swim.smime.AmqpConsumer" \
  -Dexec.args="../certs localhost 5672 swim.flight.data"

# Terminal 2
cd csharp && dotnet run -- amqp-producer ../certs ../payload/sample-flight.json \
  amqp://admin:admin@localhost:5672 swim.flight.data sign
```

### Scenario 4: Java -> Python (AMQP, Sign+Encrypt)

```bash
# Terminal 1
cd python && python amqp_consumer.py --broker localhost:5672 --queue swim.flight.data

# Terminal 2
cd java && mvn exec:java -Dexec.mainClass="com.swim.smime.AmqpProducer" \
  -Dexec.args="../certs ../payload/sample-flight.json localhost 5672 swim.flight.data sign-encrypt"
```

### Self-Tests

Each language module has a self-test that validates sign, verify, encrypt, decrypt, and sign+encrypt+decrypt+verify in isolation (no server or broker needed):

```bash
# Java
cd java && mvn -q exec:java -Dexec.mainClass="com.swim.smime.SelfTest" -Dexec.args="../certs"

# Python
cd python && python self_test.py ../certs

# C#
cd csharp && dotnet run -- test ../certs
```

### Automated Test Script

The `run-tests.sh` script automates the full test suite: builds all modules, runs the three self-tests, and executes all four cross-language scenarios. It starts and stops consumers automatically.

```bash
bash run-tests.sh           # Full suite (requires Artemis broker running)
bash run-tests.sh --no-amqp # REST scenarios only (no broker needed)
```

From PowerShell or CMD:
```
& "C:\Program Files\Git\bin\bash.exe" run-tests.sh
```

The script requires `java`, `mvn`, `python`, and `dotnet` on PATH. You can override the Python executable by setting `PYTHON` before running:

```bash
PYTHON=/path/to/python3 bash run-tests.sh
```

Expected output (all 10 checks):

```
── Phase 1: Build ──────────────────────────────────────────────────────────
  [PASS] Java compile
  [PASS] C# compile
  [PASS] Python dependencies

── Phase 2: Self-Tests ───────────────────────────────────────────────────
  [PASS] Java S/MIME self-test
  [PASS] Python S/MIME self-test
  [PASS] C# S/MIME self-test

── Phase 3: REST Scenarios (WS Light with Message Security) ─────────────
  [PASS] Scenario 1: Java->Python REST sign
  [PASS] Scenario 2: Python->C# REST sign+encrypt

── Phase 4: AMQP Scenarios (AMQP with Message Security) ─────────────────
  [PASS] Scenario 3: C#->Java AMQP sign
  [PASS] Scenario 4: Java->Python AMQP sign+encrypt

  Results: 10/10 passed, 0 failed
  ALL TESTS PASSED
```

## Code Walkthrough

Each language has a `SmimeHelper` class/module with the same four core operations:

### Sign (Integrity)

1. Create a `CMSSignedData` / `SignedCms` / `PKCS7SignatureBuilder` with SHA-256 digest
2. Encapsulate the payload inside the CMS structure (opaque signature)
3. Encode as DER, then base64

**Java** (Bouncy Castle):
```java
CMSSignedDataGenerator gen = new CMSSignedDataGenerator();
gen.addSignerInfoGenerator(new JcaSimpleSignerInfoGeneratorBuilder()
    .setProvider("BC").build("SHA256withRSA", signerKey, signerCert));
gen.addCertificates(new JcaCertStore(List.of(signerCert)));
CMSSignedData signedData = gen.generate(new CMSProcessableByteArray(payload), true);
byte[] der = signedData.toASN1Structure().getEncoded("DL"); // DER, not BER
```

**Python** (cryptography):
```python
signed_der = (PKCS7SignatureBuilder()
    .set_data(payload)
    .add_signer(signer_cert, signer_key, hashes.SHA256())
    .sign(serialization.Encoding.DER, [PKCS7Options.Binary]))
```

**C#** (.NET):
```csharp
var signedCms = new SignedCms(new ContentInfo(payload), detached: false);
var signer = new CmsSigner(SubjectIdentifierType.IssuerAndSerialNumber, signerCert);
signer.DigestAlgorithm = new Oid(HashAlgorithmName.SHA256.Name!);
signedCms.ComputeSignature(signer);
byte[] der = signedCms.Encode();
```

### Encrypt (Confidentiality)

1. Create a `CMSEnvelopedData` / `EnvelopedCms` / `PKCS7EnvelopeBuilder` with AES-256-CBC
2. Encrypt using the recipient's public certificate
3. Encode as DER, then base64

### Sign then Encrypt

```
signedDer = Sign(payload)
encryptedDer = Encrypt(signedDer)
```

### Decrypt then Verify

```
signedDer = Decrypt(encryptedDer)
payload = Verify(signedDer)
```

## Cross-Language Interoperability Notes

### DER vs BER Encoding

Java's Bouncy Castle defaults to **BER** (indefinite-length) encoding. Python's `cryptography` library only accepts **DER** (definite-length). Always use DER:

```java
// Java: use "DL" instead of default getEncoded()
signedData.toASN1Structure().getEncoded("DL");
```

### Binary Mode for Encryption

When encrypting binary data (like a signed CMS blob), ensure the library doesn't transform line endings:

```python
# Python: pass Binary option
.encrypt(serialization.Encoding.DER, [PKCS7Options.Binary])
```

### .NET Algorithm OID Constants

.NET does not expose public OID constants for CMS algorithms (unlike Java's `CMSAlgorithm`). Define your own:

```csharp
private static readonly Oid Aes256Cbc = new("2.16.840.1.101.3.4.1.42", "AES-256-CBC");
```

There is an [open proposal](https://github.com/dotnet/runtime/issues/87270) to make the internal `Oids` class public.

## Troubleshooting

| Problem | Cause | Solution |
|---|---|---|
| "Keyset does not exist" (C#) | `X509Certificate2` constructor on Windows | Use `X509CertificateLoader.LoadPkcs12()` with `EphemeralKeySet` flag |
| "ParseError: InvalidLength" (Python) | Java produced BER-encoded CMS | Use `toASN1Structure().getEncoded("DL")` in Java |
| "Identifier contains invalid JMS identifier character '-'" | JMS restricts property names | Use native AMQP 1.0 client (ProtonJ2) instead of JMS |
| bcmail vs bcjmail (Java) | `javax.mail` vs `jakarta.mail` namespace | Use `bcjmail-jdk18on` for Spring Boot 3.x / Jakarta EE |
| OpenSSL "subject name" error on Git Bash | MSYS path conversion mangles `/C=` | Add `export MSYS_NO_PATHCONV=1` |

## License

This demo project is provided for guidance purposes. Based on the EUROCONTROL Specification for SWIM Technical Infrastructure (TI) Yellow Profile, Edition 2.0 (EUROCONTROL-SPEC-170, July 2025).
