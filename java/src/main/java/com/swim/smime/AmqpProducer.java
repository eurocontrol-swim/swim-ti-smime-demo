package com.swim.smime;

import org.apache.qpid.protonj2.client.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

/**
 * AMQP Producer: reads FIXM-inspired JSON, applies S/MIME sign+encrypt,
 * and publishes to an AMQP 1.0 queue using native AMQP 1.0 client (ProtonJ2).
 * Demonstrates SWIM-TIYP-0112 over AMQP with Message Security binding.
 */
public class AmqpProducer {

    public static void main(String[] args) throws Exception {
        String certsDir = args.length > 0 ? args[0] : "../certs";
        String payloadFile = args.length > 1 ? args[1] : "../payload/sample-flight.json";
        String brokerHost = args.length > 2 ? args[2] : "localhost";
        int brokerPort = args.length > 3 ? Integer.parseInt(args[3]) : 5672;
        String queueName = args.length > 4 ? args[4] : "swim.flight.data";
        String mode = args.length > 5 ? args[5] : "sign-encrypt";

        System.out.println("=== SWIM S/MIME AMQP Producer (Java) ===");
        System.out.println("Mode: " + mode);
        System.out.println("Broker: " + brokerHost + ":" + brokerPort);
        System.out.println("Queue: " + queueName);

        byte[] payload = Files.readAllBytes(Path.of(payloadFile));
        System.out.println("Payload loaded: " + payload.length + " bytes");

        PrivateKey producerKey = SmimeHelper.loadPrivateKey(
                Path.of(certsDir, "producer.pfx").toString(), "changeit");
        X509Certificate producerCert = SmimeHelper.loadCertificateFromPfx(
                Path.of(certsDir, "producer.pfx").toString(), "changeit");

        SmimeHelper.SmimeResult result;

        if ("sign-encrypt".equals(mode)) {
            X509Certificate consumerCert = SmimeHelper.loadCertificate(
                    Path.of(certsDir, "consumer.crt").toString());
            result = SmimeHelper.signAndEncrypt(payload, "application/json",
                    producerKey, producerCert, consumerCert);
            System.out.println("Message signed and encrypted");
        } else {
            result = SmimeHelper.sign(payload, "application/json",
                    producerKey, producerCert);
            System.out.println("Message signed");
        }

        String contentType = result.contentType().replaceAll("\\r?\\n[ \\t]+", " ");
        System.out.println("S/MIME Content-Type: " + contentType);

        // Send via native AMQP 1.0 (ProtonJ2)
        Client client = Client.create();
        ConnectionOptions connOpts = new ConnectionOptions();
        connOpts.user("admin");
        connOpts.password("admin");

        try (Connection connection = client.connect(brokerHost, brokerPort, connOpts);
             Sender sender = connection.openSender(queueName)) {

            Message<byte[]> message = Message.create(result.data());

            // SWIM-TIYP-0112: content-type as AMQP core property
            message.contentType(contentType);

            // SWIM-TIYP-0112: MIME-Version as application property (with hyphen)
            message.property("MIME-Version", "1.0");

            sender.send(message);
            System.out.println("Message sent to AMQP queue: " + queueName);
        }
    }
}
