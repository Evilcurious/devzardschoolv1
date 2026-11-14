package com.erpschool.saas.access;

import com.erpschool.common.dto.ApiResponse;
import com.erpschool.feature.FeatureGuardFilter;
import com.erpschool.feature.SchoolFeature;
import com.erpschool.saas.support.Db;
import com.erpschool.tenant.context.TenantContext;
import com.erpschool.user.entity.UserRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Re-validates the session context of every school request against the database.
 *
 * <p>An access token says "user U, school S, branch B". Tokens live for minutes,
 * so this filter re-checks (with a short cache) that the claim is still true:</p>
 * <ul>
 *   <li>U still has an active membership in S, and the account is not deactivated;</li>
 *   <li>B, when present, is still an active branch of S;</li>
 *   <li>S is verified, otherwise only the application/verification screens work;</li>
 *   <li>the module being called is not switched off for branch B.</li>
 * </ul>
 * React route guards are a convenience only; this is the enforcement.
 */
@Component
public class TenantAccessGuardFilter extends OncePerRequestFilter {

    private static final long TTL_MS = 15_000;

    private final Db db;
    private final ObjectMapper objectMapper;
    private final TenantLoadTracker loadTracker;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    public TenantAccessGuardFilter(Db db, ObjectMapper objectMapper, TenantLoadTracker loadTracker) {
        this.db = db;
        this.objectMapper = objectMapper;
        this.loadTracker = loadTracker;
    }

    private record State(boolean memberActive, boolean accountActive, String verificationStatus,
                         boolean branchOk, Map<String, Object> branchPermissions) {
    }

    private record Cached(State state, long expiresAt) {
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        UserRole role = TenantContext.getRole();
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        if (role == null || userId == null) {
            chain.doFilter(request, response);
            return;
        }
        loadTracker.record(tenantId);
        if (role == UserRole.ERP_OWNER || tenantId == null) {
            chain.doFilter(request, response);
            return;
        }
        UUID campusId = TenantContext.getCampusId();
        State state = state(userId, tenantId, campusId);
        String path = request.getRequestURI();

        if (state == null || !state.memberActive() || !state.accountActive()) {
            deny(response, HttpServletResponse.SC_UNAUTHORIZED, "ACCESS_REVOKED",
                    "Your access to this school has ended. Please sign in again.");
            return;
        }
        if (!state.branchOk() && !path.startsWith("/api/v1/auth/")) {
            deny(response, HttpServletResponse.SC_FORBIDDEN, "BRANCH_UNAVAILABLE",
                    "This branch is suspended or no longer available.");
            return;
        }
        if (!"VERIFIED".equals(state.verificationStatus())
                && !allowedBeforeVerification(request.getMethod(), path)) {
            boolean suspended = "SUSPENDED".equals(state.verificationStatus());
            deny(response, HttpServletResponse.SC_FORBIDDEN, suspended ? "SCHOOL_SUSPENDED" : "SCHOOL_NOT_VERIFIED",
                    suspended ? "This school has been suspended by the platform owner. Its data is safe."
                            : "Your school is not verified yet. The ERP unlocks as soon as the platform owner approves it.");
            return;
        }
        if (campusId != null && state.branchPermissions() != null && !state.branchPermissions().isEmpty()) {
            SchoolFeature feature = FeatureGuardFilter.featureFor(path);
            if (feature != null && Boolean.FALSE.equals(state.branchPermissions().get(feature.name()))) {
                deny(response, HttpServletResponse.SC_FORBIDDEN, "BRANCH_MODULE_DISABLED",
                        "This module is turned off for your branch.");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /** What an unverified school may reach: its own application, and nothing of the ERP. */
    static boolean allowedBeforeVerification(String method, String path) {
        if (path.startsWith("/api/v1/auth/") || path.startsWith("/api/v1/school/application")
                || path.startsWith("/api/v1/public/")) {
            return true;
        }
        if ("GET".equals(method)) {
            return path.equals("/api/v1/tenants/me") || path.equals("/api/v1/features")
                    || path.equals("/api/v1/school/overview") || path.startsWith("/api/v1/files/");
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private State state(UUID userId, UUID tenantId, UUID campusId) {
        String key = userId + "|" + tenantId + "|" + campusId;
        long now = System.currentTimeMillis();
        Cached hit = cache.get(key);
        if (hit != null && hit.expiresAt() > now) {
            return hit.state();
        }
        Map<String, Object> row = db.one("""
                SELECT t.verification_status AS "verification",
                       (m.status = 'ACTIVE') AS "member",
                       (u.status <> 'INACTIVE') AS "account",
                       (c.id IS NOT NULL AND c.status = 'ACTIVE' AND c.deleted_at IS NULL
                        AND (m.role IN ('SCHOOL_OWNER', 'SCHOOL_ADMIN')
                             OR EXISTS (SELECT 1 FROM user_branch_memberships b
                                        WHERE b.user_id = :userId AND b.branch_id = c.id AND b.status = 'ACTIVE')))
                           AS "branch",
                       c.permissions AS "permissions"
                FROM tenants t
                LEFT JOIN user_school_memberships m ON m.tenant_id = t.id AND m.user_id = :userId
                LEFT JOIN users u ON u.id = :userId
                LEFT JOIN campuses c ON c.tenant_id = t.id AND c.id = CAST(:campusId AS uuid)
                WHERE t.id = :tenantId
                """, Db.p("userId", userId, "tenantId", tenantId,
                "campusId", campusId == null ? null : campusId.toString()));
        State state = row == null ? null : new State(
                Boolean.TRUE.equals(row.get("member")),
                Boolean.TRUE.equals(row.get("account")),
                (String) row.get("verification"),
                campusId == null || Boolean.TRUE.equals(row.get("branch")),
                row.get("permissions") instanceof Map<?, ?> map ? (Map<String, Object>) map : null);
        if (cache.size() > 20_000) {
            cache.entrySet().removeIf(e -> e.getValue().expiresAt() <= now);
        }
        if (state != null) {
            cache.put(key, new Cached(state, now + TTL_MS));
        }
        return state;
    }

    /** Drop cached decisions so a suspension, approval or permission change applies immediately. */
    public void evictTenant(UUID tenantId) {
        if (tenantId == null) {
            cache.clear();
            return;
        }
        String marker = "|" + tenantId + "|";
        cache.keySet().removeIf(k -> k.contains(marker));
    }

    public void evictUser(UUID userId) {
        if (userId != null) {
            String prefix = userId + "|";
            cache.keySet().removeIf(k -> k.startsWith(prefix));
        }
    }

    private void deny(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.fail(code, message));
    }
}
