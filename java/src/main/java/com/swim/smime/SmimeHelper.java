package com.swim.smime;

import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.*;
import org.bouncycastle.cms.jcajce.*;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.OutputEncryptor;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.*;

/**
 * S/MIME 4.0 helper implementing RFC 8551 for SWIM TI Yellow Profile (SWIM-TIYP-0112).
 * Uses opaque signatures (CMS SignedData) for cross-language compatibility.
 */
public class SmimeHelper {

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    public record SmimeResult(byte[] data, String contentType) {}

    // ──── SIGNING (Integrity) ────

    public static SmimeResult sign(byte[] payload, String payloadContentType,
                                   PrivateKey signerKey, X509Certificate signerCert) throws Exception {
        List<X509Certificate> certList = new ArrayList<>();
        certList.add(signerCert);
        JcaCertStore certs = new JcaCertStore(certList);

        CMSSignedDataGenerator gen = new CMSSignedDataGenerator();
        gen.addSignerInfoGenerator(
                new JcaSimpleSignerInfoGeneratorBuilder()
                        .setProvider("BC")
                        .build("SHA256withRSA", signerKey, signerCert));
        gen.addCertificates(certs);

        CMSTypedData content = new CMSProcessableByteArray(payload);
        CMSSignedData signedData = gen.generate(content, true); // true = encapsulate content

        // Use "DL" encoding (definite length) for cross-library compatibility
        byte[] derBytes = signedData.toASN1Structure().getEncoded("DL");
        String b64 = Base64.getEncoder().encodeToString(derBytes);
        String contentType = "application/pkcs7-mime; smime-type=signed-data; name=\"smime.p7m\"";

        return new SmimeResult(b64.getBytes("ASCII"), contentType);
    }

    // ──── VERIFY SIGNATURE ────

    public static byte[] verifySignature(byte[] smimeData, String contentType,
                                         X509Certificate signerCert) throws Exception {
        byte[] derBytes = Base64.getDecoder().decode(new String(smimeData, "ASCII").trim());

        CMSSignedData signedData = new CMSSignedData(derBytes);

        SignerInformationStore signerStore = signedData.getSignerInfos();
        Collection<SignerInformation> signers = signerStore.getSigners();

        for (SignerInformation signer : signers) {
            boolean valid = signer.verify(
                    new JcaSimpleSignerInfoVerifierBuilder()
                            .setProvider("BC")
                            .build(signerCert));
            if (!valid) {
                throw new SecurityException("S/MIME signature verification FAILED");
            }
        }

        CMSTypedData signedContent = signedData.getSignedContent();
        return (byte[]) signedContent.getContent();
    }

    // ──── ENCRYPT (Confidentiality) ────

    public static SmimeResult encrypt(byte[] payload, String payloadContentType,
                                      X509Certificate recipientCert) throws Exception {
        CMSEnvelopedDataGenerator gen = new CMSEnvelopedDataGenerator();
        gen.addRecipientInfoGenerator(
                new JceKeyTransRecipientInfoGenerator(recipientCert).setProvider("BC"));

        OutputEncryptor encryptor = new JceCMSContentEncryptorBuilder(CMSAlgorithm.AES256_CBC)
                .setProvider("BC").build();

        CMSEnvelopedData envelopedData = gen.generate(
                new CMSProcessableByteArray(payload), encryptor);

        // Use "DL" encoding (definite length) for cross-library compatibility
        byte[] derBytes = envelopedData.toASN1Structure().getEncoded("DL");
        String b64 = Base64.getEncoder().encodeToString(derBytes);
        String contentType = "application/pkcs7-mime; smime-type=enveloped-data; name=\"smime.p7m\"";

        return new SmimeResult(b64.getBytes("ASCII"), contentType);
    }

    // ──── DECRYPT ────

    public static byte[] decrypt(byte[] smimeData, String contentType,
                                 PrivateKey recipientKey) throws Exception {
        byte[] derBytes = Base64.getDecoder().decode(new String(smimeData, "ASCII").trim());

        CMSEnvelopedData envelopedData = new CMSEnvelopedData(derBytes);

        RecipientInformationStore recipients = envelopedData.getRecipientInfos();
        Collection<RecipientInformation> recipientInfos = recipients.getRecipients();
        Iterator<RecipientInformation> it = recipientInfos.iterator();

        if (!it.hasNext()) {
            throw new SecurityException("No recipients found in S/MIME enveloped message");
        }

        RecipientInformation recipientInfo = it.next();
        return recipientInfo.getContent(
                new JceKeyTransEnvelopedRecipient(recipientKey).setProvider("BC"));
    }

    // ──── SIGN THEN ENCRYPT ────

    public static SmimeResult signAndEncrypt(byte[] payload, String payloadContentType,
                                             PrivateKey signerKey, X509Certificate signerCert,
                                             X509Certificate recipientCert) throws Exception {
        // Sign: get raw DER
        SmimeResult signed = sign(payload, payloadContentType, signerKey, signerCert);
        byte[] signedDer = Base64.getDecoder().decode(new String(signed.data(), "ASCII"));
        // Encrypt the signed DER
        return encrypt(signedDer, "application/pkcs7-mime", recipientCert);
    }

    // ──── DECRYPT THEN VERIFY ────

    public static byte[] decryptAndVerify(byte[] smimeData, String contentType,
                                          PrivateKey recipientKey,
                                          X509Certificate signerCert) throws Exception {
        // Decrypt to get signed DER
        byte[] signedDer = decrypt(smimeData, contentType, recipientKey);
        // Re-encode as base64 for verifySignature
        String b64 = Base64.getEncoder().encodeToString(signedDer);
        return verifySignature(b64.getBytes("ASCII"),
                "application/pkcs7-mime; smime-type=signed-data", signerCert);
    }

    // ──── CERTIFICATE LOADING UTILITIES ────

    public static PrivateKey loadPrivateKey(String pfxPath, String password) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(new java.io.FileInputStream(pfxPath), password.toCharArray());
        String alias = ks.aliases().nextElement();
        return (PrivateKey) ks.getKey(alias, password.toCharArray());
    }

    public static X509Certificate loadCertificate(String certPath) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new java.io.FileInputStream(certPath));
    }

    public static X509Certificate loadCertificateFromPfx(String pfxPath, String password) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(new java.io.FileInputStream(pfxPath), password.toCharArray());
        String alias = ks.aliases().nextElement();
        return (X509Certificate) ks.getCertificate(alias);
    }
}
