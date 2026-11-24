package sched.benchmarks;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.math.ec.rfc8032.Ed25519;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import sched.adapters.http.auth.InvalidTokenException;
import sched.adapters.http.auth.JwtVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Costo de autenticar una solicitud con token (ADR 0004): la verificación completa de
 * {@link JwtVerifier}, solo la firma Ed25519 con el JDK y con BouncyCastle, y el trabajo que no es
 * criptografía (Base64 y JSON del encabezado y los claims). Sirve para decidir con números qué hace
 * falta optimizar; los resultados están en {@code bench/results/profile-server.md} y en el ADR 0004.
 *
 * <p>También mide, solo como referencia informativa (el verificador no la usa), la verificación ES256
 * (ECDSA P-256) con el JDK y con BouncyCastle, para saber cuánto se ganaría si la institución cambiara
 * de algoritmo.
 *
 * <p>Ejecutar: {@code java -jar benchmarks/target/benchmarks.jar JwtVerifyBenchmark}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class JwtVerifyBenchmark {

    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private JwtVerifier verifier;
    private PublicKey jdkKey;
    private byte[] rawKey;
    private Ed25519.PublicPoint publicPoint;
    private String token;
    private byte[] signedInput;
    private byte[] signature;
    private String headerB64;
    private String payloadB64;
    private Signature reusedJdkSignature;
    private PublicKey ecKey;
    private byte[] ecSignature;
    private ECDSASigner bcEcdsa;
    private BigInteger bcR;
    private BigInteger bcS;

    @Setup
    public void setup() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        jdkKey = pair.getPublic();
        byte[] der = jdkKey.getEncoded();
        rawKey = Arrays.copyOfRange(der, der.length - 32, der.length);
        publicPoint = Ed25519.validatePublicKeyFullExport(rawKey, 0);

        headerB64 = b64(JSON.writeValueAsBytes(Map.of("alg", "EdDSA", "typ", "JWT", "kid", "k1")));
        payloadB64 = b64(JSON.writeValueAsBytes(Map.of(
                "iss", "institucion-test", "aud", "sched-engine", "sub", "est-000123",
                "courses", List.of("MAT101", "FIS101", "QUI101", "PRO101", "ING101", "HUM101"),
                "iat", NOW.getEpochSecond(), "exp", NOW.plusSeconds(600).getEpochSecond())));
        signedInput = (headerB64 + "." + payloadB64).getBytes(StandardCharsets.US_ASCII);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(signedInput);
        signature = signer.sign();
        token = headerB64 + "." + payloadB64 + "." + b64(signature);

        verifier = new JwtVerifier(Map.of("k1", jdkKey),
                new JwtVerifier.Settings("institucion-test", "sched-engine", Duration.ofSeconds(30), Duration.ofMinutes(15)),
                Clock.fixed(NOW.plusSeconds(10), java.time.ZoneOffset.UTC));
        reusedJdkSignature = Signature.getInstance("Ed25519");
        reusedJdkSignature.initVerify(jdkKey);

        // ES256: firma en formato JWS (r || s, 64 bytes), como la llevaría un token ES256.
        KeyPairGenerator ecGen = KeyPairGenerator.getInstance("EC");
        ecGen.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair ecPair = ecGen.generateKeyPair();
        ecKey = ecPair.getPublic();
        Signature ecSigner = Signature.getInstance("SHA256withECDSAinP1363Format");
        ecSigner.initSign(ecPair.getPrivate());
        ecSigner.update(signedInput);
        ecSignature = ecSigner.sign();
        X9ECParameters curve = CustomNamedCurves.getByName("secp256r1");
        ECDomainParameters domain = new ECDomainParameters(curve);
        var w = ((ECPublicKey) ecKey).getW();
        bcEcdsa = new ECDSASigner();
        bcEcdsa.init(false, new ECPublicKeyParameters(curve.getCurve().createPoint(w.getAffineX(), w.getAffineY()), domain));
        bcR = new BigInteger(1, Arrays.copyOfRange(ecSignature, 0, 32));
        bcS = new BigInteger(1, Arrays.copyOfRange(ecSignature, 32, 64));
    }

    /** (a) La verificación completa tal como la hace el servidor por solicitud. */
    @Benchmark
    public JwtVerifier.Claims verifierComplete() throws InvalidTokenException {
        return verifier.verify(token);
    }

    /** (b) Solo la firma con el JDK, creando y preparando {@code Signature} en cada llamada. */
    @Benchmark
    public boolean jdkSignature() throws Exception {
        Signature s = Signature.getInstance("Ed25519");
        s.initVerify(jdkKey);
        s.update(signedInput);
        return s.verify(signature);
    }

    /** (b') La firma con el JDK reutilizando la instancia ya iniciada con la llave. */
    @Benchmark
    public boolean jdkSignatureReused() throws Exception {
        reusedJdkSignature.update(signedInput);
        return reusedJdkSignature.verify(signature);
    }

    /** (c) Ed25519 de BouncyCastle con la llave en bytes crudos (se decodifica en cada llamada). */
    @Benchmark
    public boolean bouncyCastleRawKey() {
        return Ed25519.verify(signature, 0, rawKey, 0, signedInput, 0, signedInput.length);
    }

    /** (c') Ed25519 de BouncyCastle con la llave ya decodificada una vez ({@code PublicPoint}). */
    @Benchmark
    public boolean bouncyCastlePublicPoint() {
        return Ed25519.verify(signature, 0, publicPoint, signedInput, 0, signedInput.length);
    }

    /** Referencia ES256 (no se usa en el verificador): ECDSA P-256 con el JDK, creando {@code Signature} por llamada. */
    @Benchmark
    public boolean es256Jdk() throws Exception {
        Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
        s.initVerify(ecKey);
        s.update(signedInput);
        return s.verify(ecSignature);
    }

    /** Referencia ES256 con BouncyCastle ({@code ECDSASigner}, curva y llave ya preparadas). */
    @Benchmark
    public boolean es256BouncyCastle() throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(signedInput);
        return bcEcdsa.verifySignature(hash, bcR, bcS);
    }

    /** Trabajo que no es firma: Base64 y JSON del encabezado y del payload, y el Base64 de la firma. */
    @Benchmark
    public int decodeAndParseJson() {
        Base64.Decoder decoder = Base64.getUrlDecoder();
        JsonNode header = JSON.readTree(decoder.decode(headerB64));
        JsonNode payload = JSON.readTree(decoder.decode(payloadB64));
        return header.size() + payload.size() + decoder.decode(b64(signature)).length;
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
