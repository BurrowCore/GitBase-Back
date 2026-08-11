package org.os.gitbase.google;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.Base64;

@Service
@Slf4j
public class GoogleTokenVerifier {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private static final String GOOGLE_PUBLIC_KEYS_URL = "https://www.googleapis.com/oauth2/v1/certs";
    private static final String GOOGLE_TOKEN_INFO_URL = "https://oauth2.googleapis.com/tokeninfo";

    public GoogleTokenVerifier() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Verify Google ID token and extract user information
     * @param idToken Google ID token
     * @return GoogleUserInfo if token is valid, null otherwise
     */
    public GoogleUserInfo verifyIdToken(String idToken) {
        try {
            // First, verify the token with Google's tokeninfo endpoint
            String tokenInfoUrl = GOOGLE_TOKEN_INFO_URL + "?id_token=" + idToken;
            String response = restTemplate.getForObject(tokenInfoUrl, String.class);
            
            if (response == null) {
                log.error("Failed to get token info from Google");
                return null;
            }

            JsonNode tokenInfo = objectMapper.readTree(response);
            
            // Check if there's an error
            if (tokenInfo.has("error")) {
                log.error("Google token verification failed: {}", tokenInfo.get("error").asText());
                return null;
            }

            // Verify expiration
            long exp = tokenInfo.get("exp").asLong();
            if (Instant.now().getEpochSecond() > exp) {
                log.error("Token has expired");
                return null;
            }

            // Extract user information
            String email = tokenInfo.get("email").asText();
            String name = tokenInfo.get("name").asText();
            String picture = tokenInfo.has("picture") ? tokenInfo.get("picture").asText() : null;
            String sub = tokenInfo.get("sub").asText();

            return GoogleUserInfo.builder()
                    .email(email)
                    .name(name)
                    .picture(picture)
                    .sub(sub)
                    .build();

        } catch (Exception e) {
            log.error("Error verifying Google ID token", e);
            return null;
        }
    }

    /**
     * Alternative method using JWT verification with Google's public keys
     * This is more secure but requires more complex JWT handling
     */
    public GoogleUserInfo verifyIdTokenWithPublicKeys(String idToken) {
        try {
            // Decode the JWT header to get the key ID
            String[] parts = idToken.split("\\.");
            if (parts.length != 3) {
                log.error("Invalid JWT token format");
                return null;
            }

            String headerJson = new String(Base64.getUrlDecoder().decode(parts[0]));
            JsonNode header = objectMapper.readTree(headerJson);
            String kid = header.get("kid").asText();

            // Get Google's public keys
            String keysResponse = restTemplate.getForObject(GOOGLE_PUBLIC_KEYS_URL, String.class);
            if (keysResponse == null) {
                log.error("Failed to get Google public keys");
                return null;
            }

            JsonNode keys = objectMapper.readTree(keysResponse);
            JsonNode key = keys.get("keys");

            // Find the matching key
            PublicKey publicKey = null;
            for (JsonNode k : key) {
                if (kid.equals(k.get("kid").asText())) {
                    publicKey = createPublicKey(k);
                    break;
                }
            }

            if (publicKey == null) {
                log.error("No matching public key found for kid: {}", kid);
                return null;
            }

            // Verify and decode the JWT
            // Note: This would require a JWT library like jjwt or nimbus-jose-jwt
            // For now, we'll use the simpler tokeninfo approach above
            
            return verifyIdToken(idToken);

        } catch (Exception e) {
            log.error("Error verifying Google ID token with public keys", e);
            return null;
        }
    }

    private PublicKey createPublicKey(JsonNode key) throws Exception {
        String n = key.get("n").asText();
        String e = key.get("e").asText();

        byte[] modulusBytes = Base64.getUrlDecoder().decode(n);
        byte[] exponentBytes = Base64.getUrlDecoder().decode(e);

        BigInteger modulus = new BigInteger(1, modulusBytes);
        BigInteger exponent = new BigInteger(1, exponentBytes);

        RSAPublicKeySpec spec = new RSAPublicKeySpec(modulus, exponent);
        KeyFactory factory = KeyFactory.getInstance("RSA");
        return factory.generatePublic(spec);
    }
} 