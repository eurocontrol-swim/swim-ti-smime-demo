"""
Python S/MIME Self Test - validates sign, verify, encrypt, decrypt,
and sign+encrypt+decrypt+verify operations in isolation.
"""

import os
import sys
from smime_helper import (
    load_private_key, load_certificate,
    sign, verify_signature, encrypt, decrypt,
    sign_and_encrypt, decrypt_and_verify
)


def main():
    certs_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.path.dirname(__file__), "..", "certs")

    print("=== Python S/MIME Self Test ===")

    pkey = load_private_key(os.path.join(certs_dir, "producer.pfx"), "changeit")
    pcert = load_certificate(os.path.join(certs_dir, "producer.pfx"), "changeit")
    ckey = load_private_key(os.path.join(certs_dir, "consumer.pfx"), "changeit")
    ccert = load_certificate(os.path.join(certs_dir, "consumer.pfx"), "changeit")
    ccert_pub = load_certificate(os.path.join(certs_dir, "consumer.crt"))

    payload = b'{"test": "hello"}'

    print("SIGN... ", end="")
    s, ct = sign(payload, "application/json", pkey, pcert)
    print(f"OK ({len(s)} bytes)")

    print("VERIFY... ", end="")
    v = verify_signature(s, ct, pcert)
    assert v == payload, "Verified payload does not match original"
    print(f"OK ({v})")

    print("ENCRYPT... ", end="")
    e, ct2 = encrypt(payload, "application/json", ccert_pub)
    print(f"OK ({len(e)} bytes)")

    print("DECRYPT... ", end="")
    d = decrypt(e, ct2, ckey, ccert)
    assert d == payload, "Decrypted payload does not match original"
    print(f"OK ({d})")

    print("SIGN+ENCRYPT... ", end="")
    se, ct3 = sign_and_encrypt(payload, "application/json", pkey, pcert, ccert_pub)
    print(f"OK ({len(se)} bytes)")

    print("DECRYPT+VERIFY... ", end="")
    dv = decrypt_and_verify(se, ct3, ckey, ccert, pcert)
    assert dv == payload, "Decrypt+Verify payload does not match original"
    print(f"OK ({dv})")

    print("ALL PYTHON S/MIME TESTS PASSED")


if __name__ == "__main__":
    main()
