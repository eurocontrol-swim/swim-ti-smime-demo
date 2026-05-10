package com.swim.smime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

@SpringBootApplication
@RestController
public class RestConsumer {

    private static String certsDir = "../certs";

    @PostMapping(value = "/receive")
    public ResponseEntity<String> receiveSmime(HttpServletRequest request) {
        try {
            String contentType = request.getContentType();
            String mimeVersion = request.getHeader("MIME-Version");
            byte[] body = request.getInputStream().readAllBytes();

            System.out.println("\n=== Received S/MIME Message ===");
            System.out.println("Content-Type: " + contentType);
            System.out.println("MIME-Version: " + mimeVersion);
            System.out.println("Body size: " + body.length + " bytes");

            byte[] payload;

            if (contentType.contains("smime-type=signed-data")) {
                X509Certificate producerCert = SmimeHelper.loadCertificate(
                        Path.of(certsDir, "producer.crt").toString());
                payload = SmimeHelper.verifySignature(body, contentType, producerCert);
                System.out.println("Signature VERIFIED successfully");

            } else if (contentType.contains("smime-type=enveloped-data")) {
                PrivateKey consumerKey = SmimeHelper.loadPrivateKey(
                        Path.of(certsDir, "consumer.pfx").toString(), "changeit");
                X509Certificate producerCert = SmimeHelper.loadCertificate(
                        Path.of(certsDir, "producer.crt").toString());
                payload = SmimeHelper.decryptAndVerify(body, contentType,
                        consumerKey, producerCert);
                System.out.println("Message DECRYPTED and signature VERIFIED");

            } else {
                return ResponseEntity.badRequest()
                        .body("Unsupported Content-Type: " + contentType);
            }

            String payloadStr = new String(payload, "UTF-8").trim();
            System.out.println("Extracted payload:\n" + payloadStr);

            return ResponseEntity.ok("OK - Payload received and verified (" + payloadStr.length() + " bytes)");

        } catch (Exception e) {
            System.err.println("Error processing S/MIME message: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        if (args.length > 0) {
            certsDir = args[0];
        }
        System.out.println("=== SWIM S/MIME REST Consumer (Java) ===");
        System.out.println("Certs dir: " + certsDir);
        SpringApplication.run(RestConsumer.class, args);
    }
}
