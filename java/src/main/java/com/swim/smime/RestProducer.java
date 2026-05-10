package com.swim.smime;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

/**
 * REST Producer: reads FIXM-inspired JSON, applies S/MIME signing (or sign+encrypt),
 * and POSTs to the consumer endpoint.
 * Demonstrates SWIM-TIYP-0112 over WS Light with Message Security binding.
 */
public class RestProducer {

    public static void main(String[] args) throws Exception {
        String certsDir = args.length > 0 ? args[0] : "../certs";
        String payloadFile = args.length > 1 ? args[1] : "../payload/sample-flight.json";
        String consumerUrl = args.length > 2 ? args[2] : "http://localhost:5000/receive";
        String mode = args.length > 3 ? args[3] : "sign"; // "sign" or "sign-encrypt"

        System.out.println("=== SWIM S/MIME REST Producer (Java) ===");
        System.out.println("Mode: " + mode);
        System.out.println("Target: " + consumerUrl);

        // Load payload
        byte[] payload = Files.readAllBytes(Path.of(payloadFile));
        System.out.println("Payload loaded: " + payload.length + " bytes");

        // Load producer credentials (for signing)
        PrivateKey producerKey = SmimeHelper.loadPrivateKey(
                Path.of(certsDir, "producer.pfx").toString(), "changeit");
        X509Certificate producerCert = SmimeHelper.loadCertificateFromPfx(
                Path.of(certsDir, "producer.pfx").toString(), "changeit");

        SmimeHelper.SmimeResult result;

        if ("sign-encrypt".equals(mode)) {
            // Load consumer certificate (for encryption)
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

        // Unfold MIME header line continuations (tabs/spaces after line breaks)
        String contentType = result.contentType().replaceAll("\\r?\\n[ \\t]+", " ");
        System.out.println("S/MIME Content-Type: " + contentType);
        System.out.println("S/MIME message size: " + result.data().length + " bytes");

        // Send via HTTP POST per SWIM-TIYP-0112 constraints
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(consumerUrl))
                .header("Content-Type", contentType)
                .header("MIME-Version", "1.0")
                .POST(HttpRequest.BodyPublishers.ofByteArray(result.data()))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        System.out.println("Response status: " + response.statusCode());
        System.out.println("Response body: " + response.body());
    }
}
