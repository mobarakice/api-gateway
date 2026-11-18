package apigw.testsupport;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Generates a throwaway PKI for tests: a CA, a server certificate (SANs for localhost, loopback and
 * container host names) and a client certificate. EC P-256 keys, PKCS#8 PEM. Test use only: keys
 * are written unencrypted to a build directory and regenerated on every run.
 *
 * <p>Run as {@code TestPki <outputDir>}. Files: {@code ca.pem}, {@code server.pem},
 * {@code server-key.pem}, {@code client.pem}, {@code client-key.pem}.
 */
public final class TestPki {

    private static final Duration VALIDITY = Duration.ofDays(2);
    private static final List<String> SERVER_DNS = List.of("localhost", "etcd", "gateway", "upstream");
    private static final List<String> SERVER_IPS = List.of("127.0.0.1", "::1");

    private TestPki() {
    }

    public static void main(String[] args) throws IOException, GeneralSecurityException {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: TestPki <outputDir>");
        }
        generate(Path.of(args[0]));
    }

    /** Writes a fresh CA, server and client certificate set into {@code dir}. */
    public static void generate(Path dir) throws IOException, GeneralSecurityException {
        Files.createDirectories(dir);
        SecureRandom random = new SecureRandom();
        KeyPair caKeys = ecKeyPair(random);
        X500Name caName = new X500Name("CN=apigw test CA");
        Instant now = Instant.now().minus(Duration.ofMinutes(5));
        Instant notAfter = now.plus(VALIDITY);

        JcaX509ExtensionUtils ext = new JcaX509ExtensionUtils();
        X509v3CertificateBuilder ca = new JcaX509v3CertificateBuilder(caName, serial(random),
                Date.from(now), Date.from(notAfter), caName, caKeys.getPublic())
                .addExtension(Extension.basicConstraints, true, new BasicConstraints(0))
                .addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign))
                .addExtension(Extension.subjectKeyIdentifier, false,
                        ext.createSubjectKeyIdentifier(caKeys.getPublic()));
        X509CertificateHolder caCert = ca.build(signer(caKeys));
        writePem(dir.resolve("ca.pem"), caCert);

        List<GeneralName> sans = new ArrayList<>();
        SERVER_DNS.forEach(n -> sans.add(new GeneralName(GeneralName.dNSName, n)));
        SERVER_IPS.forEach(ip -> sans.add(new GeneralName(GeneralName.iPAddress, ip)));
        issueLeaf(dir, "server", "CN=localhost", caName, caKeys, random, now, notAfter,
                new GeneralNames(sans.toArray(GeneralName[]::new)), KeyPurposeId.id_kp_serverAuth, ext);
        issueLeaf(dir, "client", "CN=apigw-test-client", caName, caKeys, random, now, notAfter,
                null, KeyPurposeId.id_kp_clientAuth, ext);
    }

    private static void issueLeaf(Path dir, String name, String subject, X500Name issuer, KeyPair caKeys,
            SecureRandom random, Instant notBefore, Instant notAfter, GeneralNames sans, KeyPurposeId purpose,
            JcaX509ExtensionUtils ext) throws IOException, GeneralSecurityException {
        KeyPair keys = ecKeyPair(random);
        X509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(issuer, serial(random),
                Date.from(notBefore), Date.from(notAfter), new X500Name(subject), keys.getPublic())
                .addExtension(Extension.basicConstraints, true, new BasicConstraints(false))
                .addExtension(Extension.keyUsage, true,
                        new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyAgreement))
                .addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(purpose))
                .addExtension(Extension.authorityKeyIdentifier, false,
                        ext.createAuthorityKeyIdentifier(caKeys.getPublic()));
        if (sans != null) {
            b.addExtension(Extension.subjectAlternativeName, false, sans);
        }
        writePem(dir.resolve(name + ".pem"), b.build(signer(caKeys)));
        writePem(dir.resolve(name + "-key.pem"), new JcaPKCS8Generator(keys.getPrivate(), null).generate());
    }

    private static KeyPair ecKeyPair(SecureRandom random) throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"), random);
        return g.generateKeyPair();
    }

    private static BigInteger serial(SecureRandom random) {
        return new BigInteger(64, random).abs().add(BigInteger.ONE);
    }

    private static ContentSigner signer(KeyPair keys) throws GeneralSecurityException {
        try {
            return new JcaContentSignerBuilder("SHA256withECDSA").build(keys.getPrivate());
        } catch (OperatorCreationException e) {
            throw new GeneralSecurityException(e);
        }
    }

    private static void writePem(Path file, Object object) throws IOException {
        StringWriter buffer = new StringWriter();
        try (JcaPEMWriter pem = new JcaPEMWriter(buffer)) {
            pem.writeObject(object);
        }
        write(file, buffer.toString());
    }

    private static void write(Path file, String content) {
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.US_ASCII)) {
            w.write(content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
