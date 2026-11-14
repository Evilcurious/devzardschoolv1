package com.erpschool.saas.branch;

import com.erpschool.audit.service.AuditService;
import com.erpschool.campus.entity.Campus;
import com.erpschool.campus.repository.CampusRepository;
import com.erpschool.common.cache.CatalogCache;
import com.erpschool.common.exception.BusinessException;
import com.erpschool.common.exception.DuplicateResourceException;
import com.erpschool.common.exception.ForbiddenException;
import com.erpschool.common.exception.ResourceNotFoundException;
import com.erpschool.common.util.PasswordGenerator;
import com.erpschool.feature.SchoolFeature;
import com.erpschool.saas.SaasRole;
import com.erpschool.saas.access.TenantAccessGuardFilter;
import com.erpschool.saas.limits.LimitService;
import com.erpschool.saas.support.Db;
import com.erpschool.saas.support.SaasAccess;
import com.erpschool.tenant.context.TenantContext;
import com.erpschool.user.entity.User;
import com.erpschool.user.entity.UserRole;
import com.erpschool.user.entity.UserStatus;
import com.erpschool.user.repository.UserRepository;
import com.erpschool.user.service.UsernameGenerator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Branch management for a school.
 *
 * <p>A branch is the ERP's existing "campus". Only the School Owner creates,
 * archives, secures and staffs branches; a branch session only ever sees its
 * own branch. Archiving keeps every record of the branch.</p>
 */
@Service
public class BranchService {

    private static final String SELECT = """
            SELECT c.id AS "id", c.name AS "name", c.code AS "code", c.address AS "address", c.city AS "city",
                   c.phone AS "phone", c.email AS "email", c.status AS "status", c.is_main AS "main",
                   c.permissions AS "permissions", c.created_at AS "createdAt",
                   c.branch_admin_user_id AS "adminUserId",
                   a.first_name || ' ' || a.last_name AS "adminName", a.username AS "adminUsername",
                   EXISTS (SELECT 1 FROM branch_access_credentials k WHERE k.branch_id = c.id AND k.enabled) AS "hasPassword",
                   (SELECT COUNT(*) FROM user_branch_memberships b JOIN users u ON u.id = b.user_id
                     WHERE b.branch_id = c.id AND b.status = 'ACTIVE' AND u.status <> 'INACTIVE') AS "users",
                   (SELECT COUNT(*) FROM students s WHERE s.campus_id = c.id) AS "students"
            FROM campuses c
            LEFT JOIN users a ON a.id = c.branch_admin_user_id
            """;

    private final Db db;
    private final CampusRepository campusRepository;
    private final UserRepository userRepository;
    private final UsernameGenerator usernameGenerator;
    private final PasswordEncoder passwordEncoder;
    private final LimitService limitService;
    private final AuditService auditService;
    private final CatalogCache catalogCache;
    private final TenantAccessGuardFilter accessGuard;

    public BranchService(Db db, CampusRepository campusRepository, UserRepository userRepository,
                         UsernameGenerator usernameGenerator, PasswordEncoder passwordEncoder,
                         LimitService limitService, AuditService auditService, CatalogCache catalogCache,
                         TenantAccessGuardFilter accessGuard) {
        this.db = db;
        this.campusRepository = campusRepository;
        this.userRepository = userRepository;
        this.usernameGenerator = usernameGenerator;
        this.passwordEncoder = passwordEncoder;
        this.limitService = limitService;
        this.auditService = auditService;
        this.catalogCache = catalogCache;
        this.accessGuard = accessGuard;
    }

    /** Branches the caller may see: all of them for a school-wide session, otherwise only their own. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        UUID tenantId = SaasAccess.requireManager();
        UUID sessionBranch = TenantContext.getCampusId();
        SaasRole role = TenantContext.getSaasRole();
        boolean owner = role == SaasRole.SCHOOL_OWNER;
        List<Map<String, Object>> rows;
        if (sessionBranch != null) {
            rows = db.list(SELECT + " WHERE c.tenant_id = :t AND c.id = :b", Db.p("t", tenantId, "b", sessionBranch));
        } else {
            rows = db.list(SELECT + " WHERE c.tenant_id = :t AND (:all OR c.status <> 'ARCHIVED')"
                    + " ORDER BY c.is_main DESC, (c.status = 'ARCHIVED'), c.name", Db.p("t", tenantId, "all", owner));
        }
        if (!owner) {
            rows.forEach(r -> r.remove("permissions"));
        }
        return rows;
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        UUID tenantId = SaasAccess.requireSchoolOwner();
        String name = name(body.get("name"));
        limitService.assertBranchSlot(tenantId);
        if (db.count("SELECT COUNT(*) FROM campuses WHERE tenant_id = :t AND LOWER(name) = LOWER(:n) AND status <> 'ARCHIVED'",
                Db.p("t", tenantId, "n", name)) > 0) {
            throw new DuplicateResourceException("A branch with this name already exists.");
        }
        Campus campus = new Campus();
        campus.setTenantId(tenantId);
        campus.setName(name);
        campus.setCode(nextCode(tenantId, text(body.get("code")), name));
        campus.setAddress(blankToNull(body.get("address"), 300));
        campus.setCity(blankToNull(body.get("city"), 100));
        campus.setPhone(blankToNull(body.get("phone"), 30));
        campus.setEmail(blankToNull(body.get("email"), 150));
        campus.setStatus("ACTIVE");
        campus.setMain(db.count("SELECT COUNT(*) FROM campuses WHERE tenant_id = :t AND is_main", Db.p("t", tenantId)) == 0);
        campus.setCreatedBy(SaasAccess.userId());
        // The database trigger re-checks the branch limit under a lock, so two parallel requests cannot both pass.
        campus = campusRepository.saveAndFlush(campus);
        changed(tenantId);
        auditService.record("BRANCH_CREATED", "Branch", campus.getId().toString(), Map.of("name", name));
        return get(tenantId, campus.getId());
    }

    @Transactional
    public Map<String, Object> update(UUID id, Map<String, Object> body) {
        UUID tenantId = SaasAccess.requireSchoolOwner();
        Map<String, Object> current = get(tenantId, id);
        String name = body.containsKey("name") ? name(body.get("name")) : (String) current.get("name");
        if (db.count("""
                SELECT COUNT(*) FROM campuses WHERE tenant_id = :t AND LOWER(name) = LOWER(:n) AND id <> :id
                  AND status <> 'ARCHIVED'
                """, Db.p("t", tenantId, "n", name, "id", id)) > 0) {
            throw new DuplicateResourceException("A branch with this name already exists.");
        }
        db.update("""
                UPDATE campuses SET name = :name, address = :address, city = :city, phone = :phone, email = :email,
                    updated_by = :by WHERE id = :id AND tenant_id = :t
                """, Db.p("id", id, "t", tenantId, "name", name,
                "address", body.containsKey("address") ? blankToNull(body.get("address"), 300) : current.get("address"),
                "city", body.containsKey("city") ? blankToNull(body.get("city"), 100) : current.get("city"),
                "phone", body.containsKey("phone") ? blankToNull(body.get("phone"), 30) : current.get("phone"),
                "email", body.containsKey("email") ? blankToNull(body.get("email"), 150) : current.get("email"),
                "by", SaasAccess.userId()));
        changed(tenantId);
        auditService.record("BRANCH_UPDATED", "Branch", id.toString(), Map.of("name", name));
        return get(tenantId, id);
    }

    /** ACTIVE, SUSPENDED or ARCHIVED. Nothing is deleted; an archived branch can be restored. */
    @Transactional
    public Map<String, Object> setStatus(UUID id, String status) {
        UUID tenantId = SaasAccess.requireSchoolOwner();
        Map<String, Object> current = get(tenantId, id);
        String next = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!List.of("ACTIVE", "SUSPENDED", "ARCHIVED").contains(next)) {
            throw new BusinessException("BRANCH_STATUS", "Choose active, suspended or archived.");
        }
        if (Boolean.TRUE.equals(current.get("main")) && !"ACTIVE".equals(next)) {
            throw new BusinessException("BRANCH_MAIN", "The main branch cannot be suspended or archived.");
        }
        if ("ARCHIVED".equals(current.get("status")) && !"ARCHIVED".equals(next)) {
            limitService.assertBranchSlot(tenantId);
        }
        db.update("""
                UPDATE campuses SET status = :s,
                    deleted_at = CASE WHEN :s = 'ARCHIVED' THEN COALESCE(deleted_at, NOW()) ELSE NULL END,
                    updated_by = :by
                WHERE id = :id AND tenant_id = :t
                """, Db.p("s", next, "id", id, "t", tenantId, "by", SaasAccess.userId()));
        if (!"ACTIVE".equals(next)) {
            // End sessions that were opened for this branch.
            db.update("UPDATE refresh_tokens SET revoked = TRUE, revoked_at = NOW() WHERE campus_id = :id AND NOT revoked",
                    Db.p("id", id));
        }
        changed(tenantId);
        auditService.record("BRANCH_" + next, "Branch", id.toString(), Map.of("name", current.get("name")));
        return get(tenantId, id);
    }

    /**
     * Appoint the branch admin: an existing school admin account, or a new one.
     * A new account's temporary password is returned once.
     */
    @Transactional
    public Map<String, Object> setAdmin(UUID id, Map<String, Object> body) {
        UUID tenantId = SaasAccess.requireSchoolOwner();
        Map<String, Object> branch = get(tenantId, id);
        if ("ARCHIVED".equals(branch.get("status"))) {
            throw new BusinessException("BRANCH_ARCHIVED", "Restore the branch before assigning an admin.");
        }
        String temporaryPassword = null;
        User admin;
        Object existing = body.get("userId");
        if (existing != null && !existing.toString().isBlank()) {
            UUID userId = uuid(existing.toString());
            admin = userRepository.findById(userId).filter(u -> tenantId.equals(u.getTenantId()))
                    .orElseThrow(() -> new ResourceNotFoundException("User", userId));
            if (admin.getRole() != UserRole.SCHOOL_ADMIN) {
                throw new BusinessException("BRANCH_ADMIN_ROLE", "Choose a school admin account, or create a new branch admin.");
            }
            if (db.count("""
                    SELECT COUNT(*) FROM user_school_memberships
                    WHERE user_id = :u AND tenant_id = :t AND role = 'SCHOOL_OWNER'
                    """, Db.p("u", userId, "t", tenantId)) > 0) {
                throw new BusinessException("BRANCH_ADMIN_OWNER", "The school owner already manages every branch.");
            }
            admin.setCampusId(id);
            admin.setUpdatedBy(SaasAccess.userId());
            admin = userRepository.saveAndFlush(admin);
        } else {
            String first = text(body.get("firstName"));
            String last = text(body.get("lastName"));
            String email = blankToNull(body.get("email"), 150);
            if (first.isEmpty() || last.isEmpty()) {
                throw new BusinessException("BRANCH_ADMIN_NAME", "First name and last name are required.");
            }
            if (email != null && userRepository.existsByEmailIgnoreCase(email)) {
                throw new DuplicateResourceException("Email is already in use");
            }
            limitService.assertUserSeat(tenantId);
            String code = db.scalar("SELECT code FROM tenants WHERE id = :t", Db.p("t", tenantId), String.class);
            temporaryPassword = PasswordGenerator.temporaryPassword();
            admin = new User();
            admin.setTenantId(tenantId);
            admin.setUsername(usernameGenerator.next(tenantId, code, UserRole.SCHOOL_ADMIN));
            admin.setEmail(email == null ? null : email.toLowerCase(Locale.ROOT));
            admin.setPasswordHash(passwordEncoder.encode(temporaryPassword));
            admin.setFirstName(first);
            admin.setLastName(last);
            admin.setPhone(blankToNull(body.get("phone"), 30));
            admin.setRole(UserRole.SCHOOL_ADMIN);
            admin.setStatus(UserStatus.ACTIVE);
            admin.setMustChangePassword(true);
            admin.setCampusId(id);
            admin.setCreatedBy(SaasAccess.userId());
            admin = userRepository.saveAndFlush(admin);
        }
        db.update("UPDATE campuses SET branch_admin_user_id = :u, updated_by = :by WHERE id = :id AND tenant_id = :t",
                Db.p("u", admin.getId(), "by", SaasAccess.userId(), "id", id, "t", tenantId));
        accessGuard.evictUser(admin.getId());
        changed(tenantId);
        auditService.record("BRANCH_ADMIN_ASSIGNED", "Branch", id.toString(),
                Map.of("branch", branch.get("name"), "username", admin.getUsername()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("branch", get(tenantId, id));
        out.put("username", admin.getUsername());
        out.put("temporaryPassword", temporaryPassword);
        return out;
    }

    /** Set, change or remove (blank) the optional branch access password. Stored as a bcrypt hash only. */
    @Transactional
    public Map<String, Object> setPassword(UUID id, String password) {
        UUID tenantId = SaasAccess.requireSchoolOwner();
        Map<String, Object> branch = get(tenantId, id);
        if (password == null || password.isBlank()) {
            db.update("DELETE FROM branch_access_credentials WHERE branch_id = :id AND tenant_id = :t",
                    Db.p("id", id, "t", tenantId));
            auditService.record("BRANCH_PASSWORD_REMOVED", "Branch", id.toString(), Map.of("branch", branch.get("name")));
        } else {
            if (password.length() < 6 || password.length() > 100) {
                throw new BusinessException("BRANCH_PASSWORD", "Use 6 to 100 characters for the branch password.");
            }
            db.update("""
                    INSERT INTO branch_access_credentials (branch_id, tenant_id, password_hash, enabled, updated_by)
                    VALUES (:id, :t, :hash, TRUE, :by)
                    ON CONFLICT (branch_id) DO UPDATE SET password_hash = EXCLUDED.password_hash, enabled = TRUE,
                        failed_attempts = 0, locked_until = NULL, rotated_at = NOW(), updated_by = EXCLUDED.updated_by
                    """, Db.p("id", id, "t", tenantId, "hash", passwordEncoder.encode(password), "by", SaasAccess.userId()));
            // A new password applies to everyone: sessions already inside the branch must enter it again.
            db.update("""
                    UPDATE refresh_tokens SET revoked = TRUE, revoked_at = NOW()
                    WHERE campus_id = :id AND NOT revoked AND user_id <> :me
                    """, Db.p("id", id, "me", SaasAccess.userId()));
            auditService.record("BRANCH_PASSWORD_SET", "Branch", id.toString(), Map.of("branch", branch.get("name")));
        }
        return get(tenantId, id);
    }

    /** Switch ERP modules off for one branch. A module the school itself has off stays off. */
    @Transactional
    public Map<String, Object> setPermissions(UUID id, Map<String, Object> body) {
        UUID tenantId = SaasAccess.requireSchoolOwner();
        get(tenantId, id);
        Map<String, Boolean> permissions = new LinkedHashMap<>();
        for (SchoolFeature feature : SchoolFeature.values()) {
            Object value = body.get(feature.name());
            if (value != null && !Boolean.parseBoolean(value.toString())) {
                permissions.put(feature.name(), false);
            }
        }
        db.update("UPDATE campuses SET permissions = CAST(:p AS jsonb), updated_by = :by WHERE id = :id AND tenant_id = :t",
                Db.p("p", db.json(permissions), "by", SaasAccess.userId(), "id", id, "t", tenantId));
        changed(tenantId);
        auditService.record("BRANCH_PERMISSIONS_CHANGED", "Branch", id.toString(), Map.of("disabled", permissions.keySet().toString()));
        return get(tenantId, id);
    }

    /** People who can enter a branch. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> members(UUID id) {
        UUID tenantId = SaasAccess.requireManager();
        visible(tenantId, id);
        return db.list("""
                SELECT u.id AS "id", u.username AS "username", u.first_name || ' ' || u.last_name AS "name",
                       m.role AS "role", u.status AS "status", b.is_home AS "home"
                FROM user_branch_memberships b
                JOIN users u ON u.id = b.user_id
                JOIN user_school_memberships m ON m.user_id = u.id AND m.tenant_id = b.tenant_id
                WHERE b.branch_id = :id AND b.tenant_id = :t AND m.role NOT IN ('STUDENT', 'PARENT')
                ORDER BY m.role, u.first_name, u.last_name LIMIT 500
                """, Db.p("id", id, "t", tenantId));
    }

    /** Give a staff member access to an additional branch (they will choose a branch at sign-in). */
    @Transactional
    public List<Map<String, Object>> addMember(UUID id, UUID userId) {
        UUID tenantId = SaasAccess.requireSchoolOwner();
        Map<String, Object> branch = get(tenantId, id);
        Map<String, Object> member = db.one("""
                SELECT m.role AS "role" FROM user_school_memberships m
                WHERE m.user_id = :u AND m.tenant_id = :t AND m.status = 'ACTIVE'
                """, Db.p("u", userId, "t", tenantId));
        if (member == null) {
            throw new ResourceNotFoundException("User", userId);
        }
        if (List.of("STUDENT", "PARENT", "SCHOOL_OWNER").contains(member.get("role"))) {
            throw new BusinessException("BRANCH_MEMBER_ROLE", "Only staff accounts can be given access to another branch.");
        }
        db.update("""
                INSERT INTO user_branch_memberships (user_id, tenant_id, branch_id, status, is_home, created_by)
                VALUES (:u, :t, :b, 'ACTIVE', FALSE, :by)
                ON CONFLICT (user_id, branch_id) DO UPDATE SET status = 'ACTIVE'
                """, Db.p("u", userId, "t", tenantId, "b", id, "by", SaasAccess.userId()));
        accessGuard.evictUser(userId);
        auditService.record("BRANCH_ACCESS_GRANTED", "Branch", id.toString(),
                Map.of("branch", branch.get("name"), "userId", userId.toString()));
        return members(id);
    }

    @Transactional
    public List<Map<String, Object>> removeMember(UUID id, UUID userId) {
        UUID tenantId = SaasAccess.requireSchoolOwner();
        Map<String, Object> branch = get(tenantId, id);
        int removed = db.update("""
                DELETE FROM user_branch_memberships
                WHERE user_id = :u AND branch_id = :b AND tenant_id = :t AND NOT is_home
                """, Db.p("u", userId, "b", id, "t", tenantId));
        if (removed == 0) {
            throw new BusinessException("BRANCH_MEMBER_HOME",
                    "This is the person's home branch. Move them to another branch from Users instead.");
        }
        db.update("UPDATE refresh_tokens SET revoked = TRUE, revoked_at = NOW() WHERE user_id = :u AND campus_id = :b AND NOT revoked",
                Db.p("u", userId, "b", id));
        accessGuard.evictUser(userId);
        auditService.record("BRANCH_ACCESS_REVOKED", "Branch", id.toString(),
                Map.of("branch", branch.get("name"), "userId", userId.toString()));
        return members(id);
    }

    /** Staff who could be given access to a branch or made its admin. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> staff() {
        UUID tenantId = SaasAccess.requireSchoolOwner();
        return db.list("""
                SELECT u.id AS "id", u.username AS "username", u.first_name || ' ' || u.last_name AS "name",
                       m.role AS "role", m.erp_role AS "erpRole", c.name AS "branchName"
                FROM user_school_memberships m
                JOIN users u ON u.id = m.user_id
                LEFT JOIN campuses c ON c.id = u.campus_id
                WHERE m.tenant_id = :t AND m.status = 'ACTIVE' AND u.status <> 'INACTIVE'
                  AND m.role NOT IN ('STUDENT', 'PARENT', 'SCHOOL_OWNER')
                ORDER BY u.first_name, u.last_name LIMIT 1000
                """, Db.p("t", tenantId));
    }

    private Map<String, Object> get(UUID tenantId, UUID id) {
        Map<String, Object> row = db.one(SELECT + " WHERE c.tenant_id = :t AND c.id = :id", Db.p("t", tenantId, "id", id));
        if (row == null) {
            // Also the answer for a branch of another school: its existence is not confirmed.
            throw new ResourceNotFoundException("Branch", id);
        }
        return row;
    }

    private void visible(UUID tenantId, UUID id) {
        UUID sessionBranch = TenantContext.getCampusId();
        if (sessionBranch != null && !sessionBranch.equals(id)) {
            throw new ResourceNotFoundException("Branch", id);
        }
        get(tenantId, id);
    }

    private void changed(UUID tenantId) {
        catalogCache.evictTenant(tenantId);
        accessGuard.evictTenant(tenantId);
    }

    private String nextCode(UUID tenantId, String requested, String name) {
        String base = (requested.isEmpty() ? name : requested).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (base.isEmpty()) {
            base = "BRANCH";
        }
        if (base.length() > 12) {
            base = base.substring(0, 12);
        }
        for (int i = 0; i < 200; i++) {
            String candidate = i == 0 ? base : base + (i + 1);
            if (db.count("SELECT COUNT(*) FROM campuses WHERE tenant_id = :t AND UPPER(code) = :c",
                    Db.p("t", tenantId, "c", candidate)) == 0) {
                return candidate;
            }
        }
        throw new ForbiddenException("Could not generate a branch code. Enter one manually.");
    }

    private static String name(Object value) {
        String name = text(value).replaceAll("\\s+", " ");
        if (name.length() < 2 || name.length() > 120) {
            throw new BusinessException("BRANCH_NAME", "Branch name must be 2 to 120 characters.");
        }
        return name;
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("INVALID_ID", "That selection is not valid.");
        }
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static String blankToNull(Object value, int max) {
        String t = text(value);
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > max ? t.substring(0, max) : t;
    }
}
