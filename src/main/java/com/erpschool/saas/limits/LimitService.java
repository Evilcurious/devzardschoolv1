package com.erpschool.saas.limits;

import com.erpschool.common.exception.ApiException;
import com.erpschool.saas.support.Db;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Plan limits and live usage for a school.
 *
 * <p>Limits are resolved by the database function {@code saas_effective_limits}
 * (per-school override, then the school's plan, then the default plan), the same
 * function the database triggers use, so the application and the database can
 * never disagree about a limit.</p>
 *
 * <p>Nothing here ever deletes data. A school that is over a limit (for example
 * after the Platform Owner lowers it) keeps everything; it simply cannot add more.</p>
 */
@Service
public class LimitService {

    public static final String USER_LIMIT = "USER_LIMIT_REACHED";
    public static final String BRANCH_LIMIT = "BRANCH_LIMIT_REACHED";
    public static final String STORAGE_LIMIT = "STORAGE_LIMIT_REACHED";
    private static final long MB = 1024L * 1024L;

    private final Db db;
    private final Path uploadRoot;

    public LimitService(Db db, @Value("${app.upload-dir:uploads}") String uploadDir) {
        this.db = db;
        this.uploadRoot = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    /** Effective limits of a school with where they come from. */
    @Transactional(readOnly = true)
    public Map<String, Object> limits(UUID tenantId) {
        Map<String, Object> row = db.one("""
                SELECT l.plan_code AS "planCode", l.plan_name AS "planName",
                       l.max_active_users AS "maxActiveUsers", l.storage_limit_mb AS "storageLimitMb",
                       l.ram_budget_mb AS "ramBudgetMb", l.max_branches AS "maxBranches",
                       l.resource_policy AS "resourcePolicy", l.expires_at AS "expiresAt",
                       l.plan_expired AS "planExpired", l.has_override AS "hasOverride",
                       sp.code AS "subscribedPlanCode", sp.name AS "subscribedPlanName",
                       o.max_active_users AS "overrideMaxActiveUsers", o.storage_limit_mb AS "overrideStorageLimitMb",
                       o.ram_budget_mb AS "overrideRamBudgetMb", o.max_branches AS "overrideMaxBranches",
                       o.note AS "overrideNote"
                FROM saas_effective_limits(:tenantId) l
                LEFT JOIN school_subscriptions s ON s.tenant_id = :tenantId AND s.status = 'ACTIVE'
                LEFT JOIN subscription_plans sp ON sp.id = s.plan_id
                LEFT JOIN tenant_resource_limits o ON o.tenant_id = :tenantId
                """, Db.p("tenantId", tenantId));
        return row == null ? new LinkedHashMap<>() : row;
    }

    /** Live usage, measured now from the source tables, and stored as the latest snapshot. */
    @Transactional
    public Map<String, Object> usage(UUID tenantId) {
        Map<String, Object> row = db.one("""
                INSERT INTO tenant_usage (tenant_id, active_users, total_users, branches, students, storage_bytes, measured_at)
                SELECT :tenantId,
                       saas_active_user_count(:tenantId),
                       (SELECT COUNT(*) FROM user_school_memberships m WHERE m.tenant_id = :tenantId),
                       saas_branch_count(:tenantId),
                       (SELECT COUNT(*) FROM students s WHERE s.tenant_id = :tenantId),
                       COALESCE((SELECT SUM(f.size_bytes) FROM stored_files f WHERE f.tenant_id = :tenantId), 0)
                         + COALESCE((SELECT SUM(v.size_bytes) FROM school_verifications v WHERE v.tenant_id = :tenantId), 0),
                       NOW()
                ON CONFLICT (tenant_id) DO UPDATE
                SET active_users = EXCLUDED.active_users, total_users = EXCLUDED.total_users,
                    branches = EXCLUDED.branches, students = EXCLUDED.students,
                    storage_bytes = EXCLUDED.storage_bytes, measured_at = EXCLUDED.measured_at
                RETURNING active_users AS "activeUsers", total_users AS "totalUsers", branches AS "branches",
                          students AS "students", storage_bytes AS "storageBytes", measured_at AS "measuredAt"
                """, Db.p("tenantId", tenantId));
        return row == null ? new LinkedHashMap<>() : row;
    }

    /** Limits and usage side by side, with remaining capacity, for dashboards. */
    @Transactional
    public Map<String, Object> summary(UUID tenantId) {
        Map<String, Object> limits = limits(tenantId);
        Map<String, Object> usage = usage(tenantId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("limits", limits);
        out.put("usage", usage);

        long users = num(usage.get("activeUsers"));
        long maxUsers = num(limits.get("maxActiveUsers"));
        long branches = num(usage.get("branches"));
        long maxBranches = num(limits.get("maxBranches"));
        long bytes = num(usage.get("storageBytes"));
        long maxBytes = num(limits.get("storageLimitMb")) * MB;

        out.put("users", meter(users, maxUsers));
        out.put("branches", meter(branches, maxBranches));
        out.put("storage", meter(bytes, maxBytes));
        return out;
    }

    private static Map<String, Object> meter(long used, long limit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("used", used);
        m.put("limit", limit);
        m.put("remaining", Math.max(0, limit - used));
        m.put("percent", limit <= 0 ? 0 : Math.min(999, Math.round(used * 100.0 / limit)));
        m.put("over", limit > 0 && used > limit);
        m.put("full", limit > 0 && used >= limit);
        return m;
    }

    /** Friendly pre-check before creating a user. The database trigger is the real gate. */
    @Transactional(readOnly = true)
    public void assertUserSeat(UUID tenantId) {
        Map<String, Object> limits = limits(tenantId);
        if (!blocks(limits)) {
            return;
        }
        long used = db.count("SELECT saas_active_user_count(:t)", Db.p("t", tenantId));
        long max = num(limits.get("maxActiveUsers"));
        if (used >= max) {
            throw userLimit(used, max);
        }
    }

    @Transactional(readOnly = true)
    public void assertBranchSlot(UUID tenantId) {
        Map<String, Object> limits = limits(tenantId);
        if (!blocks(limits)) {
            return;
        }
        long used = db.count("SELECT saas_branch_count(:t)", Db.p("t", tenantId));
        long max = num(limits.get("maxBranches"));
        if (used >= max) {
            throw branchLimit(used, max);
        }
    }

    /**
     * Refuse an upload that would take the school past its storage limit.
     * Must run inside the upload's transaction: it takes a per-school lock so
     * two parallel uploads cannot both squeeze under the limit.
     */
    @Transactional
    public void assertStorage(UUID tenantId, long incomingBytes) {
        db.scalar("SELECT pg_advisory_xact_lock(hashtextextended(CAST(:t AS text), 7303))::text",
                Db.p("t", tenantId.toString()), String.class);
        Map<String, Object> limits = limits(tenantId);
        if (!blocks(limits)) {
            return;
        }
        long used = storageBytes(tenantId);
        long max = num(limits.get("storageLimitMb")) * MB;
        if (used + incomingBytes > max) {
            throw new ApiException(HttpStatus.CONFLICT, STORAGE_LIMIT,
                    "Your school has used " + human(used) + " of " + human(max)
                            + " storage. This file (" + human(incomingBytes) + ") does not fit. "
                            + "Ask the platform owner for more storage.");
        }
    }

    @Transactional(readOnly = true)
    public long storageBytes(UUID tenantId) {
        return db.count("""
                SELECT COALESCE((SELECT SUM(f.size_bytes) FROM stored_files f WHERE f.tenant_id = :t), 0)
                     + COALESCE((SELECT SUM(v.size_bytes) FROM school_verifications v WHERE v.tenant_id = :t), 0)
                """, Db.p("t", tenantId));
    }

    /**
     * Files uploaded before sizes were always recorded have no size. Read them
     * from disk once so storage accounting is complete. Safe to run repeatedly.
     */
    @Transactional
    public int reconcileMissingFileSizes() {
        List<Map<String, Object>> rows = db.list("""
                SELECT id AS "id", stored_path AS "path" FROM stored_files WHERE size_bytes IS NULL LIMIT 2000
                """, Db.p());
        int fixed = 0;
        for (Map<String, Object> row : rows) {
            String path = (String) row.get("path");
            String prefix = "/api/v1/files/";
            long size = 0;
            if (path != null && path.startsWith(prefix)) {
                Path file = uploadRoot.resolve(path.substring(prefix.length())).normalize();
                if (file.startsWith(uploadRoot) && Files.isRegularFile(file)) {
                    try {
                        size = Files.size(file);
                    } catch (IOException ignored) {
                        size = 0;
                    }
                }
            }
            fixed += db.update("UPDATE stored_files SET size_bytes = :size WHERE id = :id AND size_bytes IS NULL",
                    Db.p("size", size, "id", row.get("id")));
        }
        return fixed;
    }

    /** Refresh the stored snapshot for every school in one statement. */
    @Transactional
    public void refreshAllUsage() {
        db.update("""
                INSERT INTO tenant_usage (tenant_id, active_users, total_users, branches, students, storage_bytes, measured_at)
                SELECT t.id,
                       saas_active_user_count(t.id),
                       (SELECT COUNT(*) FROM user_school_memberships m WHERE m.tenant_id = t.id),
                       saas_branch_count(t.id),
                       (SELECT COUNT(*) FROM students s WHERE s.tenant_id = t.id),
                       COALESCE((SELECT SUM(f.size_bytes) FROM stored_files f WHERE f.tenant_id = t.id), 0)
                         + COALESCE((SELECT SUM(v.size_bytes) FROM school_verifications v WHERE v.tenant_id = t.id), 0),
                       NOW()
                FROM tenants t
                ON CONFLICT (tenant_id) DO UPDATE
                SET active_users = EXCLUDED.active_users, total_users = EXCLUDED.total_users,
                    branches = EXCLUDED.branches, students = EXCLUDED.students,
                    storage_bytes = EXCLUDED.storage_bytes, measured_at = EXCLUDED.measured_at
                """, Db.p());
    }

    public static ApiException userLimit(long used, long max) {
        return new ApiException(HttpStatus.CONFLICT, USER_LIMIT,
                "Your school has reached its limit of " + max + " active users (" + used + " in use). "
                        + "Deactivate an account you no longer need, or ask the platform owner to raise the limit.");
    }

    public static ApiException branchLimit(long used, long max) {
        return new ApiException(HttpStatus.CONFLICT, BRANCH_LIMIT,
                "Your plan allows " + max + (max == 1 ? " branch" : " branches") + " and " + used
                        + (used == 1 ? " is" : " are") + " in use. Upgrade your plan to add another branch.");
    }

    /** Message carried by the database guard (SQLSTATE DZ001 / DZ002), as an API error. */
    public static ApiException fromGuard(String sqlState, String message) {
        long used = 0;
        long max = 0;
        if (message != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+) of (\\d+)").matcher(message);
            if (m.find()) {
                used = Long.parseLong(m.group(1));
                max = Long.parseLong(m.group(2));
            }
        }
        return "DZ002".equals(sqlState) ? branchLimit(used, max) : userLimit(used, max);
    }

    private static boolean blocks(Map<String, Object> limits) {
        return !limits.isEmpty() && !"WARN_ONLY".equals(limits.get("resourcePolicy"));
    }

    public static long num(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    public static String human(long bytes) {
        if (bytes >= 1024L * MB) {
            return String.format(java.util.Locale.ROOT, "%.2f GB", bytes / (1024.0 * MB));
        }
        if (bytes >= MB) {
            return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (double) MB);
        }
        return Math.max(1, bytes / 1024) + " KB";
    }
}
