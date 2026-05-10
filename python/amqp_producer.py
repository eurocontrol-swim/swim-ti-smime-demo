"""
AMQP Producer (Python): reads FIXM-inspired JSON, applies S/MIME,
and publishes to an AMQP 1.0 queue.
Demonstrates SWIM-TIYP-0112 over AMQP with Message Security binding.
"""

import os
import sys
import argparse

from smime_helper import (
    load_private_key, load_certificate,
    sign, sign_and_encrypt
)


def main():
    parser = argparse.ArgumentParser(description="SWIM S/MIME AMQP Producer (Python)")
    parser.add_argument("--certs-dir", default=os.path.join(os.path.dirname(__file__), "..", "certs"))
    parser.add_argument("--payload", default=os.path.join(os.path.dirname(__file__), "..", "payload", "sample-flight.json"))
    parser.add_argument("--broker", default="localhost:5672")
    parser.add_argument("--queue", default="swim.flight.data")
    parser.add_argument("--mode", choices=["sign", "sign-encrypt"], default="sign")
    args = parser.parse_args()

    print("=== SWIM S/MIME AMQP Producer (Python) ===")
    print(f"Mode: {args.mode}")
    print(f"Broker: {args.broker}")
    print(f"Queue: {args.queue}")

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

    try:
        from proton import Message
        from proton.handlers import MessagingHandler
        from proton.reactor import Container

        class SmimeSender(MessagingHandler):
            def __init__(self, broker, queue, smime_data, content_type):
                super().__init__()
                self.broker = broker
                self.queue = queue
                self.smime_data = smime_data
                self.content_type = content_type
                self.sent = False

            def on_start(self, event):
                conn = event.container.connect(self.broker,
                                                user="admin", password="admin",
                                                allowed_mechs="PLAIN")
                self.sender = event.container.create_sender(conn, self.queue)

            def on_sendable(self, event):
                if not self.sent:
                    msg = Message()
                    msg.body = self.smime_data
                    msg.content_type = self.content_type
                    # SWIM-TIYP-0112: MIME-Version as application property (with hyphen per spec)
                    msg.properties = {"MIME-Version": "1.0"}
                    event.sender.send(msg)
                    self.sent = True
                    print(f"Message sent to AMQP queue: {self.queue}")
                    event.connection.close()

        Container(SmimeSender(args.broker, args.queue, smime_data, content_type)).run()

    except ImportError:
        print("ERROR: python-qpid-proton is not available.")
        print("Install it via: pip install python-qpid-proton")
        sys.exit(1)


if __name__ == "__main__":
    main()
