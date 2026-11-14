package com.erpschool.saas.platform;

import com.erpschool.audit.service.AuditService;
import com.erpschool.campus.entity.Campus;
import com.erpschool.campus.repository.CampusRepository;
import com.erpschool.common.cache.CatalogCache;
import com.erpschool.common.exception.BusinessException;
import com.erpschool.common.exception.ResourceNotFoundException;
import com.erpschool.saas.access.TenantAccessGuardFilter;
import com.erpschool.saas.access.TenantLoadTracker;
import com.erpschool.saas.limits.LimitService;
import com.erpschool.saas.plan.PlanService;
import com.erpschool.saas.registration.RegistrationService;
import com.erpschool.saas.security.FileAccessService;
import com.erpschool.saas.support.Db;
import com.erpschool.saas.support.SaasAccess;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Platform Owner: every school, its verification, its plan and its resource use. */
@Service
public class PlatformSchoolService {

    private static final long MB = 1024L * 1024L;

    private final Db db;
    private final LimitService limitService;
    private final PlanService planService;
    private final RegistrationService registrationService;
    private final PlatformSettingsService settings;
    private final CampusRepository campusRepository;
    private final AuditService auditService;
    private final TenantAccessGuardFilter accessGuard;
    private final TenantLoadTracker loadTracker;
    private final CatalogCache catalogCache;
    private final FileAccessService fileAccess;
    private final Path uploadRoot;

    public PlatformSchoolService(Db db, LimitService limitService, PlanService planService,
                                 RegistrationService registrationService, PlatformSettingsService settings,
                                 CampusRepository campusRepository, AuditService auditService,
                                 TenantAccessGuardFilter accessGuard, TenantLoadTracker loadTracker,
                                 CatalogCache catalogCache, FileAccessService fileAccess,
                                 @Value("${app.upload-dir:uploads}") String uploadDir) {
        this.db = db;
        this.limitService = limitService;
        this.planService = planService;
        this.registrationService = registrationService;
        this.settings = settings;
        this.campusRepository = campusRepository;
        this.auditService = auditService;
        this.accessGuard = accessGuard;
        this.loadTracker = loadTracker;
        this.catalogCache = catalogCache;
        this.fileAccess = fileAccess;
        this.uploadRoot = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    /** All schools with status, plan, limits and usage. Usage is re-measured on each call. */
    @Transactional
    public List<Map<String, Object>> schools(String status, String query) {
        SaasAccess.requirePlatformOwner();
        limitService.refreshAllUsage();
        String q = query == null || query.isBlank() ? null : "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
        String wanted = status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)
                ? null : status.trim().toUpperCase(Locale.ROOT);
        List<Map<String, Object>> rows = db.list("""
                SELECT t.id AS "id", t.code AS "code", t.name AS "name", t.city AS "city", t.email AS "email",
                       t.phone AS "phone", t.school_type AS "schoolType", t.status AS "status",
                       t.verification_status AS "verificationStatus", t.created_at AS "registeredAt",
                       t.verified_at AS "verifiedAt",
                       a.submitted_at AS "submittedAt", a.documents_requested AS "documentsRequested",
                       (SELECT COUNT(*) FROM school_verifications d WHERE d.tenant_id = t.id) AS "documents",
                       o.first_name || ' ' || o.last_name AS "ownerName", o.email AS "ownerEmail",
                       l.plan_code AS "planCode", l.plan_name AS "planName", l.has_override AS "hasOverride",
                       l.plan_expired AS "planExpired", l.expires_at AS "planExpiresAt",
                       l.max_active_users AS "maxActiveUsers", l.storage_limit_mb AS "storageLimitMb",
                       l.ram_budget_mb AS "ramBudgetMb", l.max_branches AS "maxBranches",
                       COALESCE(u.active_users, 0) AS "activeUsers", COALESCE(u.total_users, 0) AS "totalUsers",
                       COALESCE(u.branches, 0) AS "branches", COALESCE(u.students, 0) AS "students",
                       COALESCE(u.storage_bytes, 0) AS "storageBytes"
                FROM tenants t
                LEFT JOIN school_applications a ON a.tenant_id = t.id
                LEFT JOIN users o ON o.id = t.owner_user_id
                LEFT JOIN tenant_usage u ON u.tenant_id = t.id
                LEFT JOIN LATERAL saas_effective_limits(t.id) l ON TRUE
                WHERE (CAST(:status AS text) IS NULL OR t.verification_status = :status)
                  AND (CAST(:q AS text) IS NULL OR LOWER(t.name) LIKE :q OR LOWER(t.code) LIKE :q
                       OR LOWER(COALESCE(t.email, '')) LIKE :q OR LOWER(COALESCE(t.city, '')) LIKE :q)
                ORDER BY CASE t.verification_status WHEN 'PENDING' THEN 0 WHEN 'UNDER_REVIEW' THEN 1 ELSE 2 END,
                         t.created_at DESC
                LIMIT 500
                """, Db.p("status", wanted, "q", q));
        Map<UUID, Long> load = loadTracker.snapshot();
        long totalLoad = load.values().stream().mapToLong(Long::longValue).sum();
        long heapUsedMb = heapUsedMb();
        for (Map<String, Object> row : rows) {
            long requests = load.getOrDefault((UUID) row.get("id"), 0L);
            row.put("recentRequests", requests);
            // An estimate, not a measurement: this school's share of recent requests applied to real heap use.
            row.put("estimatedRamMb", totalLoad == 0 ? 0 : Math.round(heapUsedMb * (requests / (double) totalLoad)));
        }
        return rows;
    }

    @Transactional
    public Map<String, Object> school(UUID tenantId) {
        SaasAccess.requirePlatformOwner();
        Map<String, Object> out = registrationService.view(tenantId, true);
        out.put("profile", db.one("""
                SELECT t.email AS "email", t.phone AS "phone", t.address_line AS "address", t.city AS "city",
                       t.country AS "country", t.school_type AS "schoolType",
                       t.registration_number AS "registrationNumber", t.principal_name AS "principalName",
                       t.slug AS "slug", t.notes AS "notes",
                       o.id AS "ownerId", o.first_name || ' ' || o.last_name AS "ownerName",
                       o.email AS "ownerEmail", o.username AS "ownerUsername", o.last_login_at AS "ownerLastLoginAt"
                FROM tenants t LEFT JOIN users o ON o.id = t.owner_user_id WHERE t.id = :t
                """, Db.p("t", tenantId)));
        out.put("resources", limitService.summary(tenantId));
        out.put("subscriptions", planService.history(tenantId));
        out.put("branches", db.list("""
                SELECT c.id AS "id", c.name AS "name", c.code AS "code", c.status AS "status", c.is_main AS "main",
                       (SELECT COUNT(*) FROM user_branch_memberships b WHERE b.branch_id = c.id) AS "users",
                       (SELECT COUNT(*) FROM students s WHERE s.campus_id = c.id) AS "students"
                FROM campuses c WHERE c.tenant_id = :t ORDER BY c.is_main DESC, c.name
                """, Db.p("t", tenantId)));
        out.put("recentRequests", loadTracker.recent(tenantId));
        return out;
    }

    /**
     * Verification decision.
     * UNDER_REVIEW, REQUEST_DOCUMENTS, APPROVE, REJECT, SUSPEND, REACTIVATE.
     */
    @Transactional
    public Map<String, Object> review(UUID tenantId, String action, String note) {
        SaasAccess.requirePlatformOwner();
        Map<String, Object> tenant = db.one("""
                SELECT t.verification_status AS "status", t.name AS "name" FROM tenants t WHERE t.id = :t FOR UPDATE
                """, Db.p("t", tenantId));
        if (tenant == null) {
            throw new ResourceNotFoundException("School", tenantId);
        }
        String current = (String) tenant.get("status");
        String act = action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
        String reason = note == null || note.isBlank() ? null : note.trim();
        if (reason != null && reason.length() > 2000) {
            reason = reason.substring(0, 2000);
        }
        UUID by = SaasAccess.userId();
        ensureApplication(tenantId);
        Map<String, Object> p = Db.p("t", tenantId, "by", by, "note", reason);

        switch (act) {
            case "UNDER_REVIEW" -> {
                allow(current, act, "PENDING");
                setStatus(tenantId, "UNDER_REVIEW");
                db.update("""
                        UPDATE school_applications SET status = 'UNDER_REVIEW', reviewed_by = :by, reviewed_at = NOW(),
                            review_note = COALESCE(:note, review_note) WHERE tenant_id = :t
                        """, p);
            }
            case "REQUEST_DOCUMENTS" -> {
                allow(current, act, "PENDING", "UNDER_REVIEW", "REJECTED");
                if (reason == null) {
                    throw new BusinessException("NOTE_REQUIRED", "Tell the school which documents you need.");
                }
                setStatus(tenantId, "UNDER_REVIEW");
                db.update("""
                        UPDATE school_applications SET status = 'UNDER_REVIEW', documents_requested = TRUE,
                            documents_request_note = :note, reviewed_by = :by, reviewed_at = NOW() WHERE tenant_id = :t
                        """, p);
            }
            case "APPROVE" -> {
                allow(current, act, "PENDING", "UNDER_REVIEW", "REJECTED");
                db.update("""
                        UPDATE tenants SET verification_status = 'VERIFIED', verified_at = NOW(), verified_by = :by,
                            status = 'ACTIVE' WHERE id = :t
                        """, p);
                db.update("""
                        UPDATE school_applications SET status = 'VERIFIED', documents_requested = FALSE,
                            rejection_reason = NULL, suspension_reason = NULL, reviewed_by = :by, reviewed_at = NOW(),
                            review_note = COALESCE(:note, review_note) WHERE tenant_id = :t
                        """, p);
                db.update("""
                        UPDATE school_verifications SET status = 'ACCEPTED', reviewed_by = :by, reviewed_at = NOW()
                        WHERE tenant_id = :t AND status = 'SUBMITTED'
                        """, p);
                ensureMainCampus(tenantId);
            }
            case "REJECT" -> {
                allow(current, act, "PENDING", "UNDER_REVIEW");
                if (reason == null) {
                    throw new BusinessException("NOTE_REQUIRED", "A reason is required when rejecting a school.");
                }
                setStatus(tenantId, "REJECTED");
                db.update("""
                        UPDATE school_applications SET status = 'REJECTED', rejection_reason = :note,
                            reviewed_by = :by, reviewed_at = NOW() WHERE tenant_id = :t
                        """, p);
            }
            case "SUSPEND" -> {
                allow(current, act, "VERIFIED");
                if (reason == null) {
                    throw new BusinessException("NOTE_REQUIRED", "A reason is required when suspending a school.");
                }
                // Data is kept untouched. Staff, students and parents are signed out; the owner sees the reason.
                db.update("UPDATE tenants SET verification_status = 'SUSPENDED', status = 'SUSPENDED' WHERE id = :t", p);
                db.update("""
                        UPDATE school_applications SET status = 'SUSPENDED', suspension_reason = :note,
                            reviewed_by = :by, reviewed_at = NOW() WHERE tenant_id = :t
                        """, p);
            }
            case "REACTIVATE" -> {
                allow(current, act, "SUSPENDED");
                db.update("UPDATE tenants SET verification_status = 'VERIFIED', status = 'ACTIVE' WHERE id = :t", p);
                db.update("""
                        UPDATE school_applications SET status = 'VERIFIED', suspension_reason = NULL,
                            reviewed_by = :by, reviewed_at = NOW() WHERE tenant_id = :t
                        """, p);
                ensureMainCampus(tenantId);
            }
            default -> throw new BusinessException("ACTION_UNKNOWN", "Unknown review action.");
        }
        accessGuard.evictTenant(tenantId);
        catalogCache.evictTenant(tenantId);
        catalogCache.evictPublic();
        fileAccess.evict(tenantId);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("school", tenant.get("name"));
        details.put("from", current);
        details.put("action", act);
        if (reason != null) {
            details.put("note", reason);
        }
        auditService.record(tenantId, by, null, "PLATFORM_OWNER", "SCHOOL_" + act, "Tenant",
                tenantId.toString(), details);
        return school(tenantId);
    }

    /** Accept or reject a single verification document. */
    @Transactional
    public Map<String, Object> reviewDocument(UUID tenantId, UUID documentId, String status, String note) {
        SaasAccess.requirePlatformOwner();
        String next = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!List.of("ACCEPTED", "REJECTED", "SUBMITTED").contains(next)) {
            throw new BusinessException("DOCUMENT_STATUS", "Choose accept or reject.");
        }
        int updated = db.update("""
                UPDATE school_verifications SET status = :status, review_note = :note, reviewed_by = :by,
                    reviewed_at = NOW() WHERE id = :id AND tenant_id = :t
                """, Db.p("status", next, "note", note == null || note.isBlank() ? null : note.trim(),
                "by", SaasAccess.userId(), "id", documentId, "t", tenantId));
        if (updated == 0) {
            throw new ResourceNotFoundException("Document", documentId);
        }
        return school(tenantId);
    }

    /** Platform dashboard: school counts and the real state of the server. */
    @Transactional
    public Map<String, Object> overview() {
        SaasAccess.requirePlatformOwner();
        limitService.reconcileMissingFileSizes();
        limitService.refreshAllUsage();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schools", db.one("""
                SELECT COUNT(*) AS "total",
                       COUNT(*) FILTER (WHERE verification_status = 'PENDING') AS "pending",
                       COUNT(*) FILTER (WHERE verification_status = 'UNDER_REVIEW') AS "underReview",
                       COUNT(*) FILTER (WHERE verification_status = 'VERIFIED') AS "verified",
                       COUNT(*) FILTER (WHERE verification_status = 'REJECTED') AS "rejected",
                       COUNT(*) FILTER (WHERE verification_status = 'SUSPENDED') AS "suspended"
                FROM tenants
                """, Db.p()));
        Map<String, Object> totals = db.one("""
                SELECT COALESCE(SUM(u.active_users), 0) AS "activeUsers", COALESCE(SUM(u.branches), 0) AS "branches",
                       COALESCE(SUM(u.students), 0) AS "students", COALESCE(SUM(u.storage_bytes), 0) AS "storageBytes",
                       (SELECT COALESCE(SUM(l.ram_budget_mb), 0) FROM tenants t
                          CROSS JOIN LATERAL saas_effective_limits(t.id) l
                          WHERE t.verification_status = 'VERIFIED') AS "ramBudgetAllocatedMb",
                       (SELECT COALESCE(SUM(l.storage_limit_mb), 0) FROM tenants t
                          CROSS JOIN LATERAL saas_effective_limits(t.id) l
                          WHERE t.verification_status = 'VERIFIED') AS "storageAllocatedMb",
                       pg_database_size(current_database()) AS "databaseBytes"
                FROM tenant_usage u
                """, Db.p());
        out.put("totals", totals);
        out.put("plans", db.list("""
                SELECT p.code AS "code", p.name AS "name", COUNT(s.id) AS "schools"
                FROM subscription_plans p
                LEFT JOIN school_subscriptions s ON s.plan_id = p.id AND s.status = 'ACTIVE'
                GROUP BY p.code, p.name, p.sort_order ORDER BY p.sort_order
                """, Db.p()));
        out.put("server", server());
        out.put("thresholds", settings.get("monitoring"));
        out.put("recentRequests", loadTracker.recentTotal());
        out.put("nearLimit", db.list("""
                SELECT t.id AS "id", t.name AS "name", u.active_users AS "activeUsers",
                       l.max_active_users AS "maxActiveUsers", u.branches AS "branches",
                       l.max_branches AS "maxBranches", u.storage_bytes AS "storageBytes",
                       l.storage_limit_mb AS "storageLimitMb"
                FROM tenants t
                JOIN tenant_usage u ON u.tenant_id = t.id
                CROSS JOIN LATERAL saas_effective_limits(t.id) l
                WHERE t.verification_status = 'VERIFIED'
                  AND (u.active_users >= l.max_active_users * 0.8
                       OR u.storage_bytes >= l.storage_limit_mb::numeric * 1048576 * 0.8
                       OR u.branches >= l.max_branches)
                ORDER BY (u.active_users::numeric / GREATEST(l.max_active_users, 1)) DESC
                LIMIT 20
                """, Db.p()));
        return out;
    }

    /** Real numbers from the JVM and operating system. Nothing here is estimated. */
    private Map<String, Object> server() {
        Map<String, Object> s = new LinkedHashMap<>();
        Runtime rt = Runtime.getRuntime();
        s.put("heapUsedMb", heapUsedMb());
        s.put("heapMaxMb", rt.maxMemory() / MB);
        s.put("processors", rt.availableProcessors());
        s.put("uptimeSeconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            long total = os.getTotalMemorySize();
            long available = availableMemory(os.getFreeMemorySize());
            s.put("systemRamTotalMb", total / MB);
            s.put("systemRamUsedMb", Math.max(0, total - available) / MB);
            double cpu = os.getCpuLoad();
            s.put("cpuPercent", cpu < 0 ? null : Math.round(cpu * 100));
        }
        try {
            Files.createDirectories(uploadRoot);
            FileStore store = Files.getFileStore(uploadRoot);
            s.put("diskTotalMb", store.getTotalSpace() / MB);
            s.put("diskUsedMb", Math.max(0, store.getTotalSpace() - store.getUsableSpace()) / MB);
        } catch (IOException | RuntimeException ignored) {
            /* disk figures are optional */
        }
        return s;
    }

    /** Linux reports reclaimable cache as "used"; MemAvailable is the honest figure when it exists. */
    private static long availableMemory(long fallback) {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
                if (line.startsWith("MemAvailable:")) {
                    return Long.parseLong(line.replaceAll("[^0-9]", "")) * 1024L;
                }
            }
        } catch (IOException | RuntimeException ignored) {
            /* not Linux, or not readable */
        }
        return fallback;
    }

    private static long heapUsedMb() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / MB;
    }

    private void setStatus(UUID tenantId, String status) {
        db.update("UPDATE tenants SET verification_status = :s WHERE id = :t", Db.p("s", status, "t", tenantId));
    }

    private static void allow(String current, String action, String... from) {
        for (String s : from) {
            if (s.equals(current)) {
                return;
            }
        }
        throw new BusinessException("INVALID_TRANSITION",
                "A school that is " + String.valueOf(current).toLowerCase(Locale.ROOT).replace('_', ' ')
                        + " cannot be moved with \"" + action.toLowerCase(Locale.ROOT).replace('_', ' ') + "\".");
    }

    /** Schools that existed before registration have no application row; give them one from their profile. */
    private void ensureApplication(UUID tenantId) {
        db.update("""
                INSERT INTO school_applications (tenant_id, applicant_user_id, school_name, address, city, country,
                    contact_number, official_email, principal_name, school_type, status, submitted_at)
                SELECT t.id, t.owner_user_id, t.name, COALESCE(NULLIF(t.address_line, ''), 'Not provided'),
                       COALESCE(NULLIF(t.city, ''), 'Not provided'), COALESCE(NULLIF(t.country, ''), 'Not provided'),
                       COALESCE(NULLIF(t.phone, ''), 'Not provided'), COALESCE(NULLIF(t.email, ''), 'not-provided@example.invalid'),
                       COALESCE(NULLIF(t.principal_name, ''), 'Not provided'), COALESCE(NULLIF(t.school_type, ''), 'Other'),
                       t.verification_status, t.created_at
                FROM tenants t WHERE t.id = :t
                ON CONFLICT (tenant_id) DO NOTHING
                """, Db.p("t", tenantId));
    }

    /** Approval gives every school exactly one branch to start with. */
    private void ensureMainCampus(UUID tenantId) {
        if (db.count("SELECT COUNT(*) FROM campuses WHERE tenant_id = :t", Db.p("t", tenantId)) > 0) {
            db.update("""
                    UPDATE campuses SET is_main = TRUE
                    WHERE id = (SELECT id FROM campuses WHERE tenant_id = :t AND status <> 'ARCHIVED'
                                ORDER BY created_at LIMIT 1)
                      AND NOT EXISTS (SELECT 1 FROM campuses WHERE tenant_id = :t AND is_main)
                    """, Db.p("t", tenantId));
            return;
        }
        Map<String, Object> t = db.one("""
                SELECT address_line AS "address", city AS "city", phone AS "phone", email AS "email"
                FROM tenants WHERE id = :t
                """, Db.p("t", tenantId));
        Campus campus = new Campus();
        campus.setTenantId(tenantId);
        campus.setName("Main Campus");
        campus.setCode("MAIN");
        campus.setAddress((String) t.get("address"));
        campus.setCity((String) t.get("city"));
        campus.setPhone((String) t.get("phone"));
        campus.setEmail((String) t.get("email"));
        campus.setMain(true);
        campus.setStatus("ACTIVE");
        campus.setCreatedBy(SaasAccess.userId());
        campusRepository.saveAndFlush(campus);
    }
}
