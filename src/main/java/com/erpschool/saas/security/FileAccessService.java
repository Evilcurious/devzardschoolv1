package com.erpschool.saas.security;

import com.erpschool.saas.support.Db;
import com.erpschool.security.JwtService;
import com.erpschool.tenant.context.TenantContext;
import com.erpschool.user.entity.UserRole;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides who may download an uploaded file.
 *
 * <p>Uploads used to be readable by anyone who had the URL. Now a file is served only to:</p>
 * <ol>
 *   <li>a signed-in member of the school that owns it (bearer token, or the
 *       HttpOnly file cookie set at sign-in so image and link tags keep working);</li>
 *   <li>the Platform Owner;</li>
 *   <li>anyone, when the file is media the school itself published on its public
 *       website (logo, banners, principal photo and so on).</li>
 * </ol>
 */
@Service
public class FileAccessService {

    public static final String COOKIE = "dz_files";
    private static final Duration COOKIE_TTL = Duration.ofHours(12);
    private static final long PUBLIC_TTL_MS = 60_000;
    private static final Pattern FILE_REF = Pattern.compile("/api/v1/files/([0-9a-fA-F-]{36})/([^\"'\\\\\\s?#)]+)");

    private final JwtService jwtService;
    private final Db db;
    private final ConcurrentHashMap<UUID, PublicSet> publicFiles = new ConcurrentHashMap<>();

    private record PublicSet(Set<String> names, long expiresAt) {
    }

    public FileAccessService(JwtService jwtService, Db db) {
        this.jwtService = jwtService;
        this.db = db;
    }

    public boolean canRead(HttpServletRequest request, UUID tenantId, String name) {
        UserRole role = TenantContext.getRole();
        if (role == UserRole.ERP_OWNER) {
            return true;
        }
        if (role != null && tenantId.equals(TenantContext.getTenantId())) {
            return true;
        }
        if (tenantId.equals(jwtService.parseFileToken(request.getParameter("t")))) {
            return true;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (COOKIE.equals(cookie.getName()) && tenantId.equals(jwtService.parseFileToken(cookie.getValue()))) {
                    return true;
                }
            }
        }
        return publishedNames(tenantId).contains(name);
    }

    /** Give the browser read access to this school's files for the session. */
    public void grant(HttpServletRequest request, HttpServletResponse response, UUID tenantId) {
        if (tenantId == null) {
            clear(request, response);
            return;
        }
        write(request, response, jwtService.createFileToken(tenantId, COOKIE_TTL.toSeconds()), COOKIE_TTL);
    }

    public void clear(HttpServletRequest request, HttpServletResponse response) {
        write(request, response, "", Duration.ZERO);
    }

    public String token(UUID tenantId) {
        return tenantId == null ? null : jwtService.createFileToken(tenantId, COOKIE_TTL.toSeconds());
    }

    private void write(HttpServletRequest request, HttpServletResponse response, String value, Duration maxAge) {
        boolean secure = request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
        ResponseCookie cookie = ResponseCookie.from(COOKIE, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/api/v1/files")
                .maxAge(maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /** File names referenced by content the school has published on its public website. */
    private Set<String> publishedNames(UUID tenantId) {
        long now = System.currentTimeMillis();
        PublicSet cached = publicFiles.get(tenantId);
        if (cached != null && cached.expiresAt() > now) {
            return cached.names();
        }
        Set<String> names = new HashSet<>();
        Map<String, Object> p = Db.p("t", tenantId);
        String prefix = tenantId.toString();
        for (String sql : new String[]{
                "SELECT row_to_json(x)::text AS doc FROM tenants x WHERE x.id = :t AND x.verification_status = 'VERIFIED'",
                "SELECT row_to_json(x)::text AS doc FROM campuses x WHERE x.tenant_id = :t AND x.status = 'ACTIVE'",
                "SELECT row_to_json(x)::text AS doc FROM school_settings x WHERE x.tenant_id = :t"}) {
            for (Map<String, Object> row : db.list(sql, p)) {
                Object doc = row.get("doc");
                if (doc == null) {
                    continue;
                }
                Matcher m = FILE_REF.matcher(doc.toString());
                while (m.find()) {
                    if (prefix.equalsIgnoreCase(m.group(1))) {
                        names.add(m.group(2));
                    }
                }
            }
        }
        if (publicFiles.size() > 5_000) {
            publicFiles.clear();
        }
        publicFiles.put(tenantId, new PublicSet(names, now + PUBLIC_TTL_MS));
        return names;
    }

    public void evict(UUID tenantId) {
        if (tenantId != null) {
            publicFiles.remove(tenantId);
        }
    }
}
