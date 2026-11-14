package com.erpschool.security;

import com.erpschool.config.AppProperties;
import com.erpschool.user.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_TENANT = "tenantId";
    public static final String CLAIM_CAMPUS = "campusId";
    public static final String CLAIM_USERNAME = "username";
    public static final String CLAIM_TYPE = "typ";
    public static final String TYPE_ACCESS = "access";
    /** Platform role (School Owner, Branch Admin, ...) resolved by the server at sign-in. */
    public static final String CLAIM_SAAS_ROLE = "srole";
    /** Short-lived proof that a password was verified, used only to pick a school/branch. */
    public static final String TYPE_SELECTION = "select";
    private static final long SELECTION_TTL_SECONDS = 300;

    private final SecretKey key;
    private final long accessTtlSeconds;

    public JwtService(AppProperties properties) {
        byte[] secretBytes = properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException("app.jwt.secret must be at least 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
        this.accessTtlSeconds = properties.getJwt().getAccessTokenTtlSeconds();
    }

    public String createAccessToken(User user) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(user.getId().toString())
                .claim(CLAIM_USERNAME, user.getUsername())
                .claim(CLAIM_ROLE, user.getRole().name())
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(accessTtlSeconds)));
        if (user.getTenantId() != null) {
            builder.claim(CLAIM_TENANT, user.getTenantId().toString());
        }
        if (user.getCampusId() != null) {
            builder.claim(CLAIM_CAMPUS, user.getCampusId().toString());
        }
        return builder.signWith(key).compact();
    }

    /**
     * Access token for a server-resolved session context. Tenant, branch and
     * roles come from membership records, never from the client.
     */
    public String createAccessToken(UUID userId, String username, com.erpschool.user.entity.UserRole erpRole,
                                    com.erpschool.saas.SaasRole saasRole, UUID tenantId, UUID campusId) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(userId.toString())
                .claim(CLAIM_USERNAME, username)
                .claim(CLAIM_ROLE, erpRole.name())
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(accessTtlSeconds)));
        if (saasRole != null) {
            builder.claim(CLAIM_SAAS_ROLE, saasRole.name());
        }
        if (tenantId != null) {
            builder.claim(CLAIM_TENANT, tenantId.toString());
        }
        if (campusId != null) {
            builder.claim(CLAIM_CAMPUS, campusId.toString());
        }
        return builder.signWith(key).compact();
    }

    public String createSelectionToken(UUID userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim(CLAIM_TYPE, TYPE_SELECTION)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(SELECTION_TTL_SECONDS)))
                .signWith(key)
                .compact();
    }

    /** @return the user id, or null when the token is not a valid selection token. */
    public UUID parseSelectionToken(String token) {
        try {
            Claims claims = parse(token);
            if (!TYPE_SELECTION.equals(claims.get(CLAIM_TYPE, String.class))) {
                return null;
            }
            return UUID.fromString(claims.getSubject());
        } catch (JwtException | IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * Token that only allows reading one school's uploaded files. It is sent as an
     * HttpOnly cookie scoped to /api/v1/files so that plain image and link tags
     * work without exposing the access token, and files stay private.
     */
    public String createFileToken(UUID tenantId, long ttlSeconds) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(tenantId.toString())
                .claim(CLAIM_TYPE, "file")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(key)
                .compact();
    }

    /** @return the tenant the token grants file access to, or null. */
    public UUID parseFileToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            Claims claims = parse(token);
            if (!"file".equals(claims.get(CLAIM_TYPE, String.class))) {
                return null;
            }
            return UUID.fromString(claims.getSubject());
        } catch (JwtException | IllegalArgumentException ex) {
            return null;
        }
    }

    public com.erpschool.saas.SaasRole saasRole(Claims claims) {
        return com.erpschool.saas.SaasRole.parse(claims.get(CLAIM_SAAS_ROLE, String.class));
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isValidAccessToken(String token) {
        try {
            Claims claims = parse(token);
            return TYPE_ACCESS.equals(claims.get(CLAIM_TYPE, String.class));
        } catch (JwtException | IllegalArgumentException ex) {
            return false;
        }
    }

    public UUID userId(Claims claims) {
        return UUID.fromString(claims.getSubject());
    }

    public UUID tenantId(Claims claims) {
        String value = claims.get(CLAIM_TENANT, String.class);
        return value == null ? null : UUID.fromString(value);
    }

    public UUID campusId(Claims claims) {
        String value = claims.get(CLAIM_CAMPUS, String.class);
        return value == null ? null : UUID.fromString(value);
    }

    public long getAccessTtlSeconds() {
        return accessTtlSeconds;
    }
}
