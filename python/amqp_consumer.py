"""
AMQP Consumer (Python): subscribes to an AMQP 1.0 queue, receives S/MIME messages,
verifies signatures and/or decrypts them.
Demonstrates SWIM-TIYP-0112 over AMQP with Message Security binding.
"""

import os
import sys
import argparse

from smime_helper import (
    load_certificate, load_private_key,
    verify_signature, decrypt_and_verify
)


def main():
    parser = argparse.ArgumentParser(description="SWIM S/MIME AMQP Consumer (Python)")
    parser.add_argument("--certs-dir", default=os.path.join(os.path.dirname(__file__), "..", "certs"))
    parser.add_argument("--broker", default="localhost:5672")
    parser.add_argument("--queue", default="swim.flight.data")
    args = parser.parse_args()

    print("=== SWIM S/MIME AMQP Consumer (Python) ===")
    print(f"Broker: {args.broker}")
    print(f"Queue: {args.queue}")

    producer_cert = load_certificate(os.path.join(args.certs_dir, "producer.crt"))
    consumer_key = load_private_key(os.path.join(args.certs_dir, "consumer.pfx"), "changeit")
    consumer_cert = load_certificate(os.path.join(args.certs_dir, "consumer.pfx"), "changeit")

    try:
        from proton.handlers import MessagingHandler
        from proton.reactor import Container

        class SmimeReceiver(MessagingHandler):
            def __init__(self, broker, queue):
                super().__init__()
                self.broker = broker
                self.queue = queue

            def on_start(self, event):
                conn = event.container.connect(self.broker,
                                                user="admin", password="admin",
                                                allowed_mechs="PLAIN")
                event.container.create_receiver(conn, self.queue)
                print("Waiting for messages... (press Ctrl+C to exit)")

            def on_message(self, event):
                msg = event.message
                print(f"\n=== Received AMQP Message ===")

                # SWIM-TIYP-0112: read content-type from AMQP core property
                content_type = msg.content_type or ""
                # SWIM-TIYP-0112: read MIME-Version from application property (with hyphen)
                mime_version = (msg.properties or {}).get("MIME-Version", "")
                print(f"Content-Type: {content_type}")
                print(f"MIME-Version: {mime_version}")

                body = msg.body
                if isinstance(body, str):
                    body = body.encode("utf-8")
                elif isinstance(body, bytes):
                    pass
                else:
                    body = bytes(body) if body else b""
                print(f"Body size: {len(body)} bytes")

                try:
                    if "smime-type=signed-data" in content_type:
                        payload = verify_signature(body, content_type, producer_cert)
                        print("Signature VERIFIED successfully")
                    elif "smime-type=enveloped-data" in content_type:
                        payload = decrypt_and_verify(body, content_type,
                                                      consumer_key, consumer_cert,
                                                      producer_cert)
                        print("Message DECRYPTED and signature VERIFIED")
                    else:
                        print(f"Unsupported Content-Type: {content_type}")
                        return

                    print(f"Extracted payload:\n{payload.decode('utf-8').strip()}")

                except Exception as e:
                    print(f"Error processing message: {e}", file=sys.stderr)
                    import traceback
                    traceback.print_exc()

        Container(SmimeReceiver(args.broker, args.queue)).run()

    except ImportError:
        print("ERROR: python-qpid-proton is not available on Windows.")
        print("Using fallback AMQP consumer with 'python-qpid-proton' alternative...")
        _run_fallback_consumer(args, producer_cert, consumer_key, consumer_cert)


def _run_fallback_consumer(args, producer_cert, consumer_key, consumer_cert):
    """Fallback AMQP consumer using uamqp or manual approach."""
    print("NOTE: On Windows, qpid-proton may not be available.")
    print("Install it via: pip install python-qpid-proton")
    print("Or use WSL / Linux for the AMQP Python components.")
    sys.exit(1)


if __name__ == "__main__":
    main()
