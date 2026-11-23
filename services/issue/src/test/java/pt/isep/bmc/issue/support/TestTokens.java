package pt.isep.bmc.issue.support;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Stands in for Keycloak in tests: signs access tokens with an RSA key and
 * serves the public key as a JWKS over HTTP, so the service validates tokens
 * through its real configuration (JWKS URI, issuer, audience).
 */
public final class TestTokens {

    public static final String ISSUER = "http://keycloak.test/realms/bmc";
    public static final String AUDIENCE = "bmc-api";

    private static TestTokens instance;

    private final RSAKey key;
    private final RSAKey otherKey;
    private final HttpServer server;

    private TestTokens() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("test-key").generate();
        otherKey = new RSAKeyGenerator(2048).keyID("test-key").generate(); // same kid, different key
        byte[] jwks = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/certs", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jwks.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(jwks);
            }
        });
        server.start();
    }

    public static synchronized TestTokens get() {
        if (instance == null) {
            try {
                instance = new TestTokens();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return instance;
    }

    public String jwksUri() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/certs";
    }

    /** A valid access token for {@code sub}. */
    public String token(String sub) {
        return token(sub, claims -> { });
    }

    public String token(String sub, Consumer<JWTClaimsSet.Builder> customise) {
        return sign(key, sub, customise);
    }

    /** Signed with a key the service does not trust. */
    public String forged(String sub) {
        return sign(otherKey, sub, claims -> { });
    }

    private static String sign(RSAKey signingKey, String sub, Consumer<JWTClaimsSet.Builder> customise) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(sub)
                .audience(List.of(AUDIENCE))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofMinutes(5))))
                .jwtID(UUID.randomUUID().toString());
        customise.accept(claims);
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).type(JOSEObjectType.JWT).build(),
                    claims.build());
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

}
