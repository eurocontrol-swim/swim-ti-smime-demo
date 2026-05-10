package com.swim.smime;

import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Arrays;

public class SelfTest {

    public static void main(String[] args) throws Exception {
        String certsDir = args.length > 0 ? args[0] : "../certs";

        System.out.println("=== Java S/MIME Self Test ===");

        PrivateKey signerKey = SmimeHelper.loadPrivateKey(
                Path.of(certsDir, "producer.pfx").toString(), "changeit");
        X509Certificate signerCert = SmimeHelper.loadCertificateFromPfx(
                Path.of(certsDir, "producer.pfx").toString(), "changeit");
        PrivateKey recipientKey = SmimeHelper.loadPrivateKey(
                Path.of(certsDir, "consumer.pfx").toString(), "changeit");
        X509Certificate recipientCert = SmimeHelper.loadCertificate(
                Path.of(certsDir, "consumer.crt").toString());

        byte[] payload = "{\"test\": \"hello\"}".getBytes("UTF-8");

        System.out.print("SIGN... ");
        SmimeHelper.SmimeResult signed = SmimeHelper.sign(payload, "application/json", signerKey, signerCert);
        System.out.println("OK (" + signed.data().length + " bytes)");

        System.out.print("VERIFY... ");
        byte[] verified = SmimeHelper.verifySignature(signed.data(), signed.contentType(), signerCert);
        assert Arrays.equals(verified, payload) : "Verified payload does not match original";
        System.out.println("OK (" + new String(verified, "UTF-8") + ")");

        System.out.print("ENCRYPT... ");
        SmimeHelper.SmimeResult encrypted = SmimeHelper.encrypt(payload, "application/json", recipientCert);
        System.out.println("OK (" + encrypted.data().length + " bytes)");

        System.out.print("DECRYPT... ");
        byte[] decrypted = SmimeHelper.decrypt(encrypted.data(), encrypted.contentType(), recipientKey);
        assert Arrays.equals(decrypted, payload) : "Decrypted payload does not match original";
        System.out.println("OK (" + new String(decrypted, "UTF-8") + ")");

        System.out.print("SIGN+ENCRYPT... ");
        SmimeHelper.SmimeResult se = SmimeHelper.signAndEncrypt(payload, "application/json",
                signerKey, signerCert, recipientCert);
        System.out.println("OK (" + se.data().length + " bytes)");

        System.out.print("DECRYPT+VERIFY... ");
        byte[] dv = SmimeHelper.decryptAndVerify(se.data(), se.contentType(), recipientKey, signerCert);
        assert Arrays.equals(dv, payload) : "Decrypt+Verify payload does not match original";
        System.out.println("OK (" + new String(dv, "UTF-8") + ")");

        System.out.println("ALL JAVA S/MIME TESTS PASSED");
    }
}
