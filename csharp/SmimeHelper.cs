using System.Security.Cryptography;
using System.Security.Cryptography.Pkcs;
using System.Security.Cryptography.X509Certificates;
using System.Text;

namespace SwimSmimeDemo;

public record SmimeResult(byte[] Data, string ContentType);

/// <summary>
/// S/MIME 4.0 helper implementing RFC 8551 for SWIM TI Yellow Profile (SWIM-TIYP-0112).
/// Uses opaque signatures (CMS SignedData) for cross-language compatibility.
/// </summary>
public static class SmimeHelper
{
    private static readonly Oid Aes256Cbc = new("2.16.840.1.101.3.4.1.42", "AES-256-CBC");

    // ──── SIGNING (Integrity) ────

    public static SmimeResult Sign(byte[] payload, string payloadContentType,
        X509Certificate2 signerCert)
    {
        var contentInfo = new ContentInfo(payload);
        var signedCms = new SignedCms(contentInfo, detached: false);
        var signer = new CmsSigner(SubjectIdentifierType.IssuerAndSerialNumber, signerCert);
        signer.DigestAlgorithm = new Oid(HashAlgorithmName.SHA256.Name!);
        signedCms.ComputeSignature(signer);

        byte[] derBytes = signedCms.Encode();
        string b64 = Convert.ToBase64String(derBytes);
        string ct = "application/pkcs7-mime; smime-type=signed-data; name=\"smime.p7m\"";

        return new SmimeResult(Encoding.ASCII.GetBytes(b64), ct);
    }

    // ──── VERIFY SIGNATURE ────

    public static byte[] VerifySignature(byte[] smimeData, string contentType,
        X509Certificate2 signerCert)
    {
        byte[] derBytes = Convert.FromBase64String(
            Encoding.ASCII.GetString(smimeData).Trim());

        var signedCms = new SignedCms();
        signedCms.Decode(derBytes);
        signedCms.CheckSignature(new X509Certificate2Collection(signerCert),
            verifySignatureOnly: true);

        return signedCms.ContentInfo.Content;
    }

    // ──── ENCRYPT (Confidentiality) ────

    public static SmimeResult Encrypt(byte[] payload, string payloadContentType,
        X509Certificate2 recipientCert)
    {
        var contentInfo = new ContentInfo(payload);
        var envelopedCms = new EnvelopedCms(contentInfo,
            new AlgorithmIdentifier(Aes256Cbc));
        var recipient = new CmsRecipient(
            SubjectIdentifierType.IssuerAndSerialNumber, recipientCert);
        envelopedCms.Encrypt(recipient);

        byte[] derBytes = envelopedCms.Encode();
        string b64 = Convert.ToBase64String(derBytes);
        string ct = "application/pkcs7-mime; smime-type=enveloped-data; name=\"smime.p7m\"";

        return new SmimeResult(Encoding.ASCII.GetBytes(b64), ct);
    }

    // ──── DECRYPT ────

    public static byte[] Decrypt(byte[] smimeData, string contentType,
        X509Certificate2 recipientCert)
    {
        byte[] derBytes = Convert.FromBase64String(
            Encoding.ASCII.GetString(smimeData).Trim());

        var envelopedCms = new EnvelopedCms();
        envelopedCms.Decode(derBytes);
        envelopedCms.Decrypt(new X509Certificate2Collection(recipientCert));

        return envelopedCms.ContentInfo.Content;
    }

    // ──── SIGN THEN ENCRYPT ────

    public static SmimeResult SignAndEncrypt(byte[] payload, string payloadContentType,
        X509Certificate2 signerCert, X509Certificate2 recipientCert)
    {
        var signed = Sign(payload, payloadContentType, signerCert);
        byte[] signedDer = Convert.FromBase64String(
            Encoding.ASCII.GetString(signed.Data));
        return Encrypt(signedDer, "application/pkcs7-mime", recipientCert);
    }

    // ──── DECRYPT THEN VERIFY ────

    public static byte[] DecryptAndVerify(byte[] smimeData, string contentType,
        X509Certificate2 recipientCert, X509Certificate2 signerCert)
    {
        byte[] signedDer = Decrypt(smimeData, contentType, recipientCert);
        string b64 = Convert.ToBase64String(signedDer);
        return VerifySignature(Encoding.ASCII.GetBytes(b64),
            "application/pkcs7-mime; smime-type=signed-data", signerCert);
    }

    // ──── CERTIFICATE LOADING ────

    public static X509Certificate2 LoadCertificateWithKey(string pfxPath, string password)
    {
        byte[] certData = File.ReadAllBytes(pfxPath);
        return X509CertificateLoader.LoadPkcs12(certData, password,
            X509KeyStorageFlags.Exportable | X509KeyStorageFlags.EphemeralKeySet);
    }

    public static X509Certificate2 LoadCertificate(string certPath)
    {
        byte[] certData = File.ReadAllBytes(certPath);
        return X509CertificateLoader.LoadCertificate(certData);
    }
}
