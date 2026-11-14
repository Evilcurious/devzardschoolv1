package com.erpschool.saas.access;

import com.erpschool.audit.service.AuditService;
import com.erpschool.common.exception.ForbiddenException;
import com.erpschool.common.exception.UnauthorizedException;
import com.erpschool.saas.SaasRole;
import com.erpschool.saas.access.SchoolOption.BranchOption;
import com.erpschool.saas.support.Db;
import com.erpschool.user.entity.UserRole;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Central login routing.
 *
 * <p>After a password is verified this service works out, from membership
 * records only, which schools and branches the user may enter. Nothing the
 * browser sends is trusted: a requested school or branch is accepted only if it
 * appears in the list this service computed for that user.</p>
 */
@Service
public class AccessContextService {

    private static final int BRANCH_PASSWORD_MAX_FAILURES = 5;
    private static final int BRANCH_PASSWORD_LOCK_MINUTES = 15;

    private final Db db;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public AccessContextService(Db db, PasswordEncoder passwordEncoder, AuditService auditService) {
        this.db = db;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    /** Outcome of routing a login: either one resolved context, or a private list to choose from. */
    public record Resolution(AccessContext context, List<SchoolOption> options) {
        public boolean selectionRequired() {
            return context == null;
        }
    }

    /** Schools (and their branches) this user is authorised for. Never includes anything else. */
    @Transactional(readOnly = true)
    public List<SchoolOption> options(UUID userId) {
        List<Map<String, Object>> memberships = db.list("""
                SELECT m.tenant_id AS "tenantId", m.role AS "role", m.erp_role AS "erpRole",
                       t.name AS "name", t.logo_url AS "logoUrl", t.status AS "status",
                       t.verification_status AS "verificationStatus",
                       EXISTS (SELECT 1 FROM user_branch_memberships b
                               WHERE b.user_id = m.user_id AND b.tenant_id = m.tenant_id) AS "assigned"
                FROM user_school_memberships m
                JOIN tenants t ON t.id = m.tenant_id
                WHERE m.user_id = :userId AND m.status = 'ACTIVE'
                ORDER BY t.name
                """, Db.p("userId", userId));
        if (memberships.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> branchRows = db.list("""
                SELECT c.tenant_id AS "tenantId", c.id AS "id", c.name AS "name", c.is_main AS "main",
                       (bac.branch_id IS NOT NULL) AS "requiresPassword",
                       (ubm.user_id IS NOT NULL) AS "member"
                FROM campuses c
                JOIN user_school_memberships m
                     ON m.tenant_id = c.tenant_id AND m.user_id = :userId AND m.status = 'ACTIVE'
                LEFT JOIN user_branch_memberships ubm
                     ON ubm.branch_id = c.id AND ubm.user_id = :userId AND ubm.status = 'ACTIVE'
                LEFT JOIN branch_access_credentials bac ON bac.branch_id = c.id AND bac.enabled
                WHERE c.status = 'ACTIVE' AND c.deleted_at IS NULL
                ORDER BY c.is_main DESC, c.name
                """, Db.p("userId", userId));

        List<SchoolOption> out = new ArrayList<>();
        for (Map<String, Object> m : memberships) {
            UUID tenantId = (UUID) m.get("tenantId");
            SaasRole role = SaasRole.valueOf((String) m.get("role"));
            UserRole erpRole = UserRole.valueOf((String) m.get("erpRole"));
            boolean assigned = Boolean.TRUE.equals(m.get("assigned"));
            boolean owner = role == SaasRole.SCHOOL_OWNER;

            List<BranchOption> all = new ArrayList<>();
            List<BranchOption> mine = new ArrayList<>();
            for (Map<String, Object> b : branchRows) {
                if (!tenantId.equals(b.get("tenantId"))) {
                    continue;
                }
                BranchOption option = new BranchOption((UUID) b.get("id"), (String) b.get("name"),
                        Boolean.TRUE.equals(b.get("main")),
                        // The owner manages the branch passwords and is never asked for one.
                        !owner && Boolean.TRUE.equals(b.get("requiresPassword")));
                all.add(option);
                if (Boolean.TRUE.equals(b.get("member"))) {
                    mine.add(option);
                }
            }

            boolean allBranches;
            List<BranchOption> branches;
            String blocked = null;
            if (role.schoolWide()) {
                // Owner and school-wide admins: the whole school, or any single branch they choose.
                allBranches = true;
                branches = all.size() > 1 ? all : List.of();
            } else if (!assigned) {
                // Never tied to a branch: school-wide roles, and accounts that predate branches.
                if (role == SaasRole.BRANCH_ADMIN) {
                    allBranches = false;
                    branches = List.of();
                    blocked = "You have not been assigned to a branch yet. Ask your school owner.";
                } else {
                    allBranches = true;
                    branches = List.of();
                }
            } else {
                allBranches = false;
                branches = mine;
                if (mine.isEmpty()) {
                    blocked = "Your branch is suspended or no longer available. Contact your school owner.";
                }
            }
            out.add(new SchoolOption(tenantId, (String) m.get("name"), (String) m.get("logoUrl"), role, erpRole,
                    (String) m.get("status"), (String) m.get("verificationStatus"), allBranches, branches, blocked));
        }
        return out;
    }

    /**
     * Route a freshly authenticated user.
     * One school with one possible branch context goes straight in; anything
     * ambiguous returns the private list instead (unless the remembered last
     * selection is still valid).
     */
    @Transactional
    public Resolution resolveLogin(UUID userId) {
        List<SchoolOption> options = options(userId);
        if (options.isEmpty()) {
            throw new UnauthorizedException("Your account is not linked to an active school.");
        }
        List<SchoolOption> usable = options.stream().filter(o -> o.choices() > 0).toList();
        if (usable.isEmpty()) {
            throw new UnauthorizedException(options.get(0).blockedReason() == null
                    ? "Your account has no active school access." : options.get(0).blockedReason());
        }
        boolean canSwitch = usable.size() > 1 || usable.get(0).choices() > 1;

        // Several possibilities: honour the remembered selection if it is still authorised.
        AccessContext remembered = remembered(userId, usable, canSwitch);
        if (remembered != null) {
            return new Resolution(remembered, options);
        }
        if (usable.size() == 1 && usable.get(0).allBranches()) {
            // School-wide role in a single school: open the whole school; branches are one click away.
            return new Resolution(remember(userId, context(usable.get(0), null, canSwitch)), options);
        }
        if (usable.size() == 1 && usable.get(0).choices() == 1) {
            SchoolOption only = usable.get(0);
            if (only.allBranches()) {
                return new Resolution(remember(userId, context(only, null, canSwitch)), options);
            }
            BranchOption branch = only.branches().get(0);
            if (!branch.requiresPassword()) {
                return new Resolution(remember(userId, context(only, branch, canSwitch)), options);
            }
            return new Resolution(null, usable);
        }

        return new Resolution(null, usable);
    }

    private AccessContext remembered(UUID userId, List<SchoolOption> usable, boolean canSwitch) {
        if (!canSwitch) {
            return null;
        }
        Map<String, Object> last = db.one("""
                SELECT last_tenant_id AS "tenantId", last_campus_id AS "campusId" FROM users WHERE id = :id
                """, Db.p("id", userId));
        if (last == null || last.get("tenantId") == null) {
            return null;
        }
        UUID lastTenant = (UUID) last.get("tenantId");
        UUID lastCampus = (UUID) last.get("campusId");
        for (SchoolOption option : usable) {
            if (!option.tenantId().equals(lastTenant)) {
                continue;
            }
            if (lastCampus == null) {
                return option.allBranches() ? context(option, null, canSwitch) : null;
            }
            for (BranchOption branch : option.branches()) {
                if (branch.id().equals(lastCampus) && !branch.requiresPassword()) {
                    return context(option, branch, canSwitch);
                }
            }
        }
        return null;
    }

    /**
     * Enter a specific school / branch. The pair must be one this user is
     * authorised for; a branch access password is checked when the branch has one.
     */
    @Transactional(noRollbackFor = {ForbiddenException.class, UnauthorizedException.class})
    public AccessContext select(UUID userId, UUID tenantId, UUID campusId, String branchPassword) {
        AccessContext ctx = authorise(userId, tenantId, campusId, true, branchPassword);
        return remember(userId, ctx);
    }

    /** Re-check an existing session (token refresh). No password prompt, but membership and branch must still be valid. */
    @Transactional(readOnly = true)
    public AccessContext revalidate(UUID userId, UUID tenantId, UUID campusId) {
        return authorise(userId, tenantId, campusId, false, null);
    }

    private AccessContext authorise(UUID userId, UUID tenantId, UUID campusId,
                                    boolean checkPassword, String branchPassword) {
        if (tenantId == null) {
            throw new UnauthorizedException("Choose a school to continue.");
        }
        List<SchoolOption> options = options(userId);
        List<SchoolOption> usable = options.stream().filter(o -> o.choices() > 0).toList();
        SchoolOption school = options.stream().filter(o -> o.tenantId().equals(tenantId)).findFirst().orElse(null);
        if (school == null) {
            // Same answer whether the school exists or not: never confirm other schools.
            throw new ForbiddenException("ACCESS_DENIED", "You do not have access to that school.");
        }
        if (school.choices() == 0) {
            throw new ForbiddenException("BRANCH_UNAVAILABLE", school.blockedReason() == null
                    ? "No branch is available for your account." : school.blockedReason());
        }
        boolean canSwitch = usable.size() > 1 || school.choices() > 1;
        if (campusId == null) {
            if (school.allBranches()) {
                return context(school, null, canSwitch);
            }
            if (school.branches().size() == 1) {
                campusId = school.branches().get(0).id();
            } else {
                throw new ForbiddenException("BRANCH_REQUIRED", "Choose a branch to continue.");
            }
        }
        UUID wanted = campusId;
        BranchOption branch = school.branches().stream().filter(b -> b.id().equals(wanted)).findFirst().orElse(null);
        if (branch == null) {
            throw new ForbiddenException("ACCESS_DENIED", "You do not have access to that branch.");
        }
        if (checkPassword && branch.requiresPassword()) {
            verifyBranchPassword(userId, tenantId, branch, branchPassword);
        }
        return context(school, branch, canSwitch);
    }

    private void verifyBranchPassword(UUID userId, UUID tenantId, BranchOption branch, String password) {
        Map<String, Object> cred = db.one("""
                SELECT password_hash AS "hash", locked_until AS "lockedUntil"
                FROM branch_access_credentials
                WHERE branch_id = :branchId AND tenant_id = :tenantId AND enabled
                """, Db.p("branchId", branch.id(), "tenantId", tenantId));
        if (cred == null) {
            return;
        }
        Instant lockedUntil = (Instant) cred.get("lockedUntil");
        if (lockedUntil != null && lockedUntil.isAfter(Instant.now())) {
            throw new ForbiddenException("BRANCH_LOCKED",
                    "Too many wrong branch passwords. Try again in a few minutes.");
        }
        if (password == null || password.isBlank()) {
            throw new ForbiddenException("BRANCH_PASSWORD_REQUIRED", "This branch needs its access password.");
        }
        if (!passwordEncoder.matches(password, (String) cred.get("hash"))) {
            db.update("""
                    UPDATE branch_access_credentials
                    SET failed_attempts = failed_attempts + 1,
                        locked_until = CASE WHEN failed_attempts + 1 >= :max
                                            THEN NOW() + make_interval(mins => :minutes) ELSE locked_until END
                    WHERE branch_id = :branchId
                    """, Db.p("branchId", branch.id(), "max", BRANCH_PASSWORD_MAX_FAILURES,
                    "minutes", BRANCH_PASSWORD_LOCK_MINUTES));
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("branchId", branch.id().toString());
            details.put("branch", branch.name());
            auditService.record(tenantId, userId, null, null, "BRANCH_ACCESS_DENIED", "Branch",
                    branch.id().toString(), details);
            throw new ForbiddenException("BRANCH_PASSWORD_INVALID", "The branch access password is not correct.");
        }
        db.update("""
                UPDATE branch_access_credentials SET failed_attempts = 0, locked_until = NULL
                WHERE branch_id = :branchId AND (failed_attempts > 0 OR locked_until IS NOT NULL)
                """, Db.p("branchId", branch.id()));
    }

    private AccessContext remember(UUID userId, AccessContext ctx) {
        db.update("""
                UPDATE users SET last_tenant_id = :tenantId, last_campus_id = CAST(:campusId AS uuid)
                WHERE id = :id AND (last_tenant_id IS DISTINCT FROM :tenantId
                                    OR last_campus_id IS DISTINCT FROM CAST(:campusId AS uuid))
                """, Db.p("id", userId, "tenantId", ctx.tenantId(),
                "campusId", ctx.campusId() == null ? null : ctx.campusId().toString()));
        return ctx;
    }

    private static AccessContext context(SchoolOption school, BranchOption branch, boolean canSwitch) {
        return new AccessContext(school.tenantId(), school.name(),
                branch == null ? null : branch.id(), branch == null ? null : branch.name(),
                school.role(), school.erpRole(), school.verificationStatus(), canSwitch);
    }

    /** Wire shape of the current context, shared by the auth endpoints. */
    public static Map<String, Object> describe(AccessContext ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("role", ctx.role().name());
        out.put("tenantId", ctx.tenantId());
        out.put("tenantName", ctx.tenantName());
        out.put("branchId", ctx.campusId());
        out.put("branchName", ctx.campusName());
        out.put("allBranches", ctx.allBranches());
        out.put("verificationStatus", ctx.verificationStatus());
        out.put("canSwitch", ctx.canSwitch());
        return out;
    }

    public static Map<String, Object> describePlatformOwner() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("role", SaasRole.PLATFORM_OWNER.name());
        out.put("allBranches", true);
        out.put("canSwitch", false);
        return out;
    }

    static boolean same(UUID a, UUID b) {
        return Objects.equals(a, b);
    }
}
