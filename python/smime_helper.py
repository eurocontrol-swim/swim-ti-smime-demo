"""
S/MIME 4.0 helper implementing RFC 8551 for SWIM TI Yellow Profile (SWIM-TIYP-0112).
Uses the 'cryptography' library (PyCA) for CMS/PKCS#7 signing and encryption,
and 'asn1crypto' for CMS SignedData parsing and content extraction.

Uses opaque signatures (application/pkcs7-mime; smime-type=signed-data) rather than
multipart/signed to ensure cross-language compatibility and alignment with
SWIM-TIYP-0112's "single data section" requirement.
"""

import base64

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.hazmat.primitives.serialization.pkcs7 import (
    PKCS7EnvelopeBuilder,
    PKCS7SignatureBuilder,
    PKCS7Options,
    pkcs7_decrypt_der,
)
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.x509.oid import NameOID

from asn1crypto import cms, core


def load_private_key(path, password=None):
    with open(path, "rb") as f:
        data = f.read()
    if path.endswith(".pfx") or path.endswith(".p12"):
        key, _, _ = pkcs12.load_key_and_certificates(
            data, (password or "").encode())
        return key
    return serialization.load_pem_private_key(
        data, password=password.encode() if password else None)


def load_certificate(path, password=None):
    with open(path, "rb") as f:
        data = f.read()
    if path.endswith(".pfx") or path.endswith(".p12"):
        _, cert, _ = pkcs12.load_key_and_certificates(
            data, (password or "").encode())
        return cert
    return x509.load_pem_x509_certificate(data)


# ──── SIGNING (Integrity) ────

def sign(payload: bytes, payload_content_type: str,
         signer_key, signer_cert) -> tuple:
    """
    Create an S/MIME opaque signed message (application/pkcs7-mime; smime-type=signed-data).
    The payload is embedded inside the CMS SignedData structure.
    Returns (base64_encoded_der_bytes, content_type).
    """
    signed_der = (
        PKCS7SignatureBuilder()
        .set_data(payload)
        .add_signer(signer_cert, signer_key, hashes.SHA256())
        .sign(serialization.Encoding.DER, [PKCS7Options.Binary])
    )

    b64 = base64.b64encode(signed_der).decode("ascii")
    content_type = 'application/pkcs7-mime; smime-type=signed-data; name="smime.p7m"'

    return b64.encode("ascii"), content_type


# ──── VERIFY SIGNATURE ────

def verify_signature(smime_data: bytes, content_type: str,
                     signer_cert) -> bytes:
    """
    Verify an opaque S/MIME signed message and return the original payload.
    Uses asn1crypto for CMS parsing and cryptography for signature verification.
    """
    try:
        der_data = base64.b64decode(smime_data)
    except Exception:
        der_data = smime_data

    # Parse the CMS ContentInfo / SignedData using asn1crypto
    content_info = cms.ContentInfo.load(der_data)
    signed_data = content_info['content']

    # Extract the encapsulated content
    encap_content_info = signed_data['encap_content_info']
    content_bytes = encap_content_info['content'].native

    # Verify each signer
    signer_infos = signed_data['signer_infos']
    for signer_info in signer_infos:
        sig_bytes = signer_info['signature'].native
        digest_algo = signer_info['digest_algorithm']['algorithm'].native
        signed_attrs = signer_info['signed_attrs']

        if signed_attrs:
            # Verify signature over the DER-encoded signed attributes
            signed_attrs_der = signed_attrs.dump()
            # Replace the implicit [0] tag with SET tag for verification
            signed_attrs_der = b'\x31' + signed_attrs_der[1:]

            signer_cert.public_key().verify(
                sig_bytes,
                signed_attrs_der,
                padding.PKCS1v15(),
                hashes.SHA256()
            )
        else:
            # Verify signature directly over the content
            signer_cert.public_key().verify(
                sig_bytes,
                content_bytes,
                padding.PKCS1v15(),
                hashes.SHA256()
            )

    cn = signer_cert.subject.get_attributes_for_oid(NameOID.COMMON_NAME)
    print(f"  Signer CN: {cn[0].value if cn else 'unknown'}")
    print(f"  Signature VERIFIED cryptographically")

    return content_bytes


# ──── ENCRYPT (Confidentiality) ────

def encrypt(payload: bytes, payload_content_type: str,
            recipient_cert) -> tuple:
    """
    Create an S/MIME enveloped (encrypted) message.
    Returns (base64_encoded_der_bytes, content_type).
    """
    enveloped_der = (
        PKCS7EnvelopeBuilder()
        .set_data(payload)
        .add_recipient(recipient_cert)
        .encrypt(serialization.Encoding.DER, [PKCS7Options.Binary])
    )

    b64 = base64.b64encode(enveloped_der).decode("ascii")
    content_type = 'application/pkcs7-mime; smime-type=enveloped-data; name="smime.p7m"'

    return b64.encode("ascii"), content_type


# ──── DECRYPT ────

def decrypt(smime_data: bytes, content_type: str,
            recipient_key, recipient_cert) -> bytes:
    """Decrypt an S/MIME enveloped message."""
    try:
        encrypted_der = base64.b64decode(smime_data)
    except Exception:
        encrypted_der = smime_data

    return pkcs7_decrypt_der(encrypted_der, recipient_cert, recipient_key, [])


# ──── SIGN THEN ENCRYPT ────

def sign_and_encrypt(payload: bytes, payload_content_type: str,
                     signer_key, signer_cert, recipient_cert) -> tuple:
    """Sign the payload, then encrypt the signed DER blob."""
    signed_b64, _ = sign(payload, payload_content_type, signer_key, signer_cert)
    signed_der = base64.b64decode(signed_b64)
    return encrypt(signed_der, "application/pkcs7-mime", recipient_cert)


# ──── DECRYPT THEN VERIFY ────

def decrypt_and_verify(smime_data: bytes, content_type: str,
                       recipient_key, recipient_cert, signer_cert) -> bytes:
    """Decrypt, then verify the inner signed message."""
    signed_der = decrypt(smime_data, content_type, recipient_key, recipient_cert)
    signed_b64 = base64.b64encode(signed_der)
    return verify_signature(signed_b64,
                            "application/pkcs7-mime; smime-type=signed-data", signer_cert)
