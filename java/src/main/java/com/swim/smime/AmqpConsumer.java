package com.swim.smime;

import org.apache.qpid.protonj2.client.*;

import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

/**
 * AMQP Consumer: subscribes to an AMQP 1.0 queue using native AMQP 1.0 client (ProtonJ2),
 * receives S/MIME messages, verifies signatures and/or decrypts them.
 * Demonstrates SWIM-TIYP-0112 over AMQP with Message Security binding.
 */
public class AmqpConsumer {

    public static void main(String[] args) throws Exception {
        String certsDir = args.length > 0 ? args[0] : "../certs";
        String brokerHost = args.length > 1 ? args[1] : "localhost";
        int brokerPort = args.length > 2 ? Integer.parseInt(args[2]) : 5672;
        String queueName = args.length > 3 ? args[3] : "swim.flight.data";

        System.out.println("=== SWIM S/MIME AMQP Consumer (Java) ===");
        System.out.println("Broker: " + brokerHost + ":" + brokerPort);
        System.out.println("Queue: " + queueName);
        System.out.println("Waiting for messages... (press Ctrl+C to exit)");

        PrivateKey consumerKey = SmimeHelper.loadPrivateKey(
                Path.of(certsDir, "consumer.pfx").toString(), "changeit");
        X509Certificate producerCert = SmimeHelper.loadCertificate(
                Path.of(certsDir, "producer.crt").toString());

        Client client = Client.create();
        ConnectionOptions connOpts = new ConnectionOptions();
        connOpts.user("admin");
        connOpts.password("admin");

        try (Connection connection = client.connect(brokerHost, brokerPort, connOpts);
             Receiver receiver = connection.openReceiver(queueName)) {

            while (true) {
                Delivery delivery = receiver.receive();
                Message<?> msg = delivery.message();

                System.out.println("\n=== Received AMQP Message ===");

                // SWIM-TIYP-0112: read content-type from AMQP core property
                String contentType = msg.contentType();
                // SWIM-TIYP-0112: read MIME-Version from application property (with hyphen)
                Object mimeVersion = msg.property("MIME-Version");

                System.out.println("Content-Type: " + contentType);
                System.out.println("MIME-Version: " + mimeVersion);

                byte[] body;
                Object bodyObj = msg.body();
                if (bodyObj instanceof byte[] bytes) {
                    body = bytes;
                } else {
                    body = bodyObj.toString().getBytes("UTF-8");
                }
                System.out.println("Body size: " + body.length + " bytes");

                try {
                    byte[] payload;

                    if (contentType != null && contentType.contains("smime-type=signed-data")) {
                        payload = SmimeHelper.verifySignature(body, contentType, producerCert);
                        System.out.println("Signature VERIFIED successfully");

                    } else if (contentType != null && contentType.contains("smime-type=enveloped-data")) {
                        payload = SmimeHelper.decryptAndVerify(body, contentType,
                                consumerKey, producerCert);
                        System.out.println("Message DECRYPTED and signature VERIFIED");

                    } else {
                        System.err.println("Unsupported Content-Type: " + contentType);
                        continue;
                    }

                    System.out.println("Extracted payload:\n" + new String(payload, "UTF-8").trim());
                    System.out.flush();

                } catch (Exception e) {
                    System.err.println("Error processing message: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }
    }
}
