package sched.server;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/** Una PKI mínima para las pruebas: CA, certificados de servidor y de cliente, escritos en PEM. */
final class TestPki {

    /** Certificado y llave de una entidad (CA, servidor o cliente). */
    record Identity(X509Certificate certificate, KeyPair keys) {

        /** Escribe {@code <name>.crt} y {@code <name>.key} (PKCS#8) en {@code dir}. */
        Pem write(Path dir, String name) throws IOException, GeneralSecurityException {
            Path cert = dir.resolve(name + ".crt");
            Path key = dir.resolve(name + ".key");
            Files.writeString(cert, pem("CERTIFICATE", certificate.getEncoded()));
            Files.writeString(key, pem("PRIVATE KEY", keys.getPrivate().getEncoded()));
            return new Pem(cert, key);
        }
    }

    record Pem(Path cert, Path key) {
    }

    private TestPki() {
    }

    static Identity newCa(String commonName) throws Exception {
        KeyPair keys = newKeys();
        X500Name name = new X500Name("CN=" + commonName);
        var builder = builder(name, name, keys);
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
        return new Identity(sign(builder, keys), keys);
    }

    /** Certificado de servidor válido para {@code localhost} y {@code 127.0.0.1}. */
    static Identity newServer(Identity ca, String commonName) throws Exception {
        KeyPair keys = newKeys();
        var builder = builder(name(ca), new X500Name("CN=" + commonName), keys);
        builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(new GeneralName[] {
                new GeneralName(GeneralName.dNSName, "localhost"),
                new GeneralName(GeneralName.iPAddress, "127.0.0.1")}));
        builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
        return new Identity(sign(builder, ca.keys()), keys);
    }

    static Identity newClient(Identity ca, String commonName) throws Exception {
        KeyPair keys = newKeys();
        var builder = builder(name(ca), new X500Name("CN=" + commonName), keys);
        builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth));
        return new Identity(sign(builder, ca.keys()), keys);
    }

    private static X500Name name(Identity ca) {
        return new X500Name(ca.certificate().getSubjectX500Principal().getName());
    }

    private static KeyPair newKeys() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static JcaX509v3CertificateBuilder builder(X500Name issuer, X500Name subject, KeyPair subjectKeys) {
        Instant now = Instant.now();
        return new JcaX509v3CertificateBuilder(issuer, new BigInteger(64, new java.security.SecureRandom()),
                Date.from(now.minusSeconds(60)), Date.from(now.plusSeconds(3600)), subject, subjectKeys.getPublic());
    }

    private static X509Certificate sign(JcaX509v3CertificateBuilder builder, KeyPair signer) throws Exception {
        return new JcaX509CertificateConverter()
                .getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(signer.getPrivate())));
    }

    private static String pem(String label, byte[] der) {
        return "-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END " + label + "-----\n";
    }
}
