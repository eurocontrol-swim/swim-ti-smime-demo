"""
REST Consumer (Python/Flask): receives S/MIME messages via HTTP POST,
verifies signatures and/or decrypts them.
Demonstrates SWIM-TIYP-0112 over WS Light with Message Security binding.
"""

import os
import sys
from flask import Flask, request, jsonify
from smime_helper import (
    load_certificate, load_private_key,
    verify_signature, decrypt_and_verify
)

app = Flask(__name__)

CERTS_DIR = os.environ.get("CERTS_DIR",
    os.path.join(os.path.dirname(__file__), "..", "certs"))


@app.route("/receive", methods=["POST"])
def receive_smime():
    content_type = request.content_type
    mime_version = request.headers.get("MIME-Version")
    body = request.get_data()

    print(f"\n=== Received S/MIME Message ===")
    print(f"Content-Type: {content_type}")
    print(f"MIME-Version: {mime_version}")
    print(f"Body size: {len(body)} bytes")

    try:
        producer_cert = load_certificate(os.path.join(CERTS_DIR, "producer.crt"))

        if "smime-type=signed-data" in content_type:
            payload = verify_signature(body, content_type, producer_cert)
            print("Signature VERIFIED successfully")

        elif "smime-type=enveloped-data" in content_type:
            consumer_key = load_private_key(
                os.path.join(CERTS_DIR, "consumer.pfx"), "changeit")
            consumer_cert = load_certificate(
                os.path.join(CERTS_DIR, "consumer.pfx"), "changeit")
            payload = decrypt_and_verify(body, content_type,
                                          consumer_key, consumer_cert,
                                          producer_cert)
            print("Message DECRYPTED and signature VERIFIED")

        else:
            return jsonify({"error": f"Unsupported Content-Type: {content_type}"}), 400

        payload_str = payload.decode("utf-8").strip()
        print(f"Extracted payload:\n{payload_str}")

        return jsonify({
            "status": "OK",
            "message": f"Payload received and verified ({len(payload_str)} bytes)"
        }), 200

    except Exception as e:
        print(f"Error processing S/MIME message: {e}", file=sys.stderr)
        import traceback
        traceback.print_exc()
        return jsonify({"error": str(e)}), 500


if __name__ == "__main__":
    print("=== SWIM S/MIME REST Consumer (Python) ===")
    print(f"Certs dir: {CERTS_DIR}")
    app.run(host="0.0.0.0", port=5000, debug=False)
