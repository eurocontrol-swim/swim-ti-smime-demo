"""
REST Producer (Python): reads FIXM-inspired JSON, applies S/MIME sign+encrypt,
and POSTs to the consumer endpoint.
Demonstrates SWIM-TIYP-0112 over WS Light with Message Security binding.
"""

import os
import sys
import argparse
import requests
from smime_helper import (
    load_private_key, load_certificate,
    sign, sign_and_encrypt
)


def main():
    parser = argparse.ArgumentParser(description="SWIM S/MIME REST Producer (Python)")
    parser.add_argument("--certs-dir", default=os.path.join(os.path.dirname(__file__), "..", "certs"))
    parser.add_argument("--payload", default=os.path.join(os.path.dirname(__file__), "..", "payload", "sample-flight.json"))
    parser.add_argument("--url", default="http://localhost:8443/receive")
    parser.add_argument("--mode", choices=["sign", "sign-encrypt"], default="sign-encrypt")
    args = parser.parse_args()

    print("=== SWIM S/MIME REST Producer (Python) ===")
    print(f"Mode: {args.mode}")
    print(f"Target: {args.url}")

    with open(args.payload, "rb") as f:
        payload = f.read()
    print(f"Payload loaded: {len(payload)} bytes")

    producer_key = load_private_key(os.path.join(args.certs_dir, "producer.pfx"), "changeit")
    producer_cert = load_certificate(os.path.join(args.certs_dir, "producer.pfx"), "changeit")

    if args.mode == "sign-encrypt":
        consumer_cert = load_certificate(os.path.join(args.certs_dir, "consumer.crt"))
        smime_data, content_type = sign_and_encrypt(
            payload, "application/json",
            producer_key, producer_cert, consumer_cert)
        print("Message signed and encrypted")
    else:
        smime_data, content_type = sign(
            payload, "application/json",
            producer_key, producer_cert)
        print("Message signed")

    print(f"S/MIME Content-Type: {content_type}")
    print(f"S/MIME message size: {len(smime_data)} bytes")

    # Send via HTTP POST per SWIM-TIYP-0112 constraints
    headers = {
        "Content-Type": content_type,
        "MIME-Version": "1.0",
    }

    response = requests.post(args.url, data=smime_data, headers=headers)
    print(f"Response status: {response.status_code}")
    print(f"Response body: {response.text}")


if __name__ == "__main__":
    main()
