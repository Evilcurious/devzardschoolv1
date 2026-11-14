package com.erpschool.saas.plan;

import com.erpschool.audit.service.AuditService;
import com.erpschool.common.exception.BusinessException;
import com.erpschool.common.exception.DuplicateResourceException;
import com.erpschool.common.exception.ResourceNotFoundException;
import com.erpschool.saas.access.TenantAccessGuardFilter;
import com.erpschool.saas.limits.LimitService;
import com.erpschool.saas.support.Db;
import com.erpschool.saas.support.SaasAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Subscription plans, a school's plan, and per-school limit overrides.
 *
 * <p>Online payment is deliberately not implemented. A plan change is recorded
 * with an optional payment provider name and external reference, so a payment
 * gateway can later create exactly the same record.</p>
 */
@Service
public class PlanService {

    private static final String SELECT = """
            SELECT p.id AS "id", p.code AS "code", p.name AS "name", p.description AS "description",
                   p.price_monthly AS "priceMonthly", p.price_yearly AS "priceYearly", p.currency AS "currency",
                   p.max_active_users AS "maxActiveUsers", p.storage_limit_mb AS "storageLimitMb",
                   p.ram_budget_mb AS "ramBudgetMb", p.max_branches AS "maxBranches",
                   p.modules AS "modules", p.features AS "features", p.resource_policy AS "resourcePolicy",
                   p.duration_days AS "durationDays", p.is_public AS "isPublic", p.is_active AS "isActive",
                   p.is_default AS "isDefault", p.sort_order AS "sortOrder",
                   (SELECT COUNT(*) FROM school_subscriptions s WHERE s.plan_id = p.id AND s.status = 'ACTIVE') AS "schools"
            FROM subscription_plans p
            """;

    private final Db db;
    private final AuditService auditService;
    private final LimitService limitService;
    private final TenantAccessGuardFilter accessGuard;

    public PlanService(Db db, AuditService auditService, LimitService limitService,
                       TenantAccessGuardFilter accessGuard) {
        this.db = db;
        this.auditService = auditService;
        this.limitService = limitService;
        this.accessGuard = accessGuard;
    }

    /** Plans shown on the public pricing page and to schools: active and public only. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> publicPlans() {
        List<Map<String, Object>> plans = db.list(SELECT + " WHERE p.is_active AND p.is_public ORDER BY p.sort_order, p.name",
                Db.p());
        plans.forEach(p -> p.remove("schools"));
        return plans;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> all() {
        SaasAccess.requirePlatformOwner();
        return db.list(SELECT + " ORDER BY p.sort_order, p.name", Db.p());
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        SaasAccess.requirePlatformOwner();
        String code = text(body.get("code")).toUpperCase().replaceAll("[^A-Z0-9_]", "_");
        if (!code.matches("^[A-Z0-9_]{2,30}$")) {
            throw new BusinessException("PLAN_CODE", "Plan code must be 2 to 30 letters, digits or underscores.");
        }
        if (db.count("SELECT COUNT(*) FROM subscription_plans WHERE code = :code", Db.p("code", code)) > 0) {
            throw new DuplicateResourceException("A plan with this code already exists.");
        }
        Map<String, Object> p = values(body, null);
        p.put("code", code);
        p.put("by", SaasAccess.userId());
        if (Boolean.TRUE.equals(p.get("isDefault"))) {
            db.update("UPDATE subscription_plans SET is_default = FALSE WHERE is_default", Db.p());
        }
        Map<String, Object> row = db.one("""
                INSERT INTO subscription_plans (code, name, description, price_monthly, price_yearly, currency,
                    max_active_users, storage_limit_mb, ram_budget_mb, max_branches, modules, features,
                    resource_policy, duration_days, is_public, is_active, is_default, sort_order, updated_by)
                VALUES (:code, :name, :description, :priceMonthly, :priceYearly, :currency,
                    :maxActiveUsers, :storageLimitMb, :ramBudgetMb, :maxBranches, CAST(:modules AS jsonb),
                    CAST(:features AS jsonb), :resourcePolicy, :durationDays, :isPublic, :isActive, :isDefault,
                    :sortOrder, :by)
                RETURNING id AS "id"
                """, p);
        auditService.record("PLAN_CREATED", "SubscriptionPlan", String.valueOf(row.get("id")), Map.of("code", code));
        return get((UUID) row.get("id"));
    }

    @Transactional
    public Map<String, Object> update(UUID id, Map<String, Object> body) {
        SaasAccess.requirePlatformOwner();
        Map<String, Object> current = get(id);
        Map<String, Object> p = values(body, current);
        p.put("id", id);
        p.put("by", SaasAccess.userId());
        boolean isDefault = Boolean.TRUE.equals(p.get("isDefault"));
        if (Boolean.TRUE.equals(current.get("isDefault")) && !isDefault) {
            throw new BusinessException("PLAN_DEFAULT", "Choose another default plan first; one plan must stay the default.");
        }
        if (isDefault && !Boolean.TRUE.equals(p.get("isActive"))) {
            throw new BusinessException("PLAN_DEFAULT", "The default plan cannot be deactivated.");
        }
        if (isDefault && !Boolean.TRUE.equals(current.get("isDefault"))) {
            db.update("UPDATE subscription_plans SET is_default = FALSE WHERE is_default", Db.p());
        }
        db.update("""
                UPDATE subscription_plans SET name = :name, description = :description,
                    price_monthly = :priceMonthly, price_yearly = :priceYearly, currency = :currency,
                    max_active_users = :maxActiveUsers, storage_limit_mb = :storageLimitMb,
                    ram_budget_mb = :ramBudgetMb, max_branches = :maxBranches,
                    modules = CAST(:modules AS jsonb), features = CAST(:features AS jsonb),
                    resource_policy = :resourcePolicy, duration_days = :durationDays, is_public = :isPublic,
                    is_active = :isActive, is_default = :isDefault, sort_order = :sortOrder, updated_by = :by
                WHERE id = :id
                """, p);
        // Lowering a limit never removes anything: schools above it keep their data and are only stopped from adding more.
        auditService.record("PLAN_UPDATED", "SubscriptionPlan", id.toString(), Map.of("code", current.get("code")));
        return get(id);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(UUID id) {
        Map<String, Object> row = db.one(SELECT + " WHERE p.id = :id", Db.p("id", id));
        if (row == null) {
            throw new ResourceNotFoundException("Plan", id);
        }
        return row;
    }

    /** Put a school on a plan. The previous subscription is kept as history. */
    @Transactional
    public Map<String, Object> assign(UUID tenantId, Map<String, Object> body) {
        SaasAccess.requirePlatformOwner();
        String code = text(body.get("planCode")).toUpperCase();
        Map<String, Object> plan = db.one(SELECT + " WHERE p.code = :code", Db.p("code", code));
        if (plan == null || !Boolean.TRUE.equals(plan.get("isActive"))) {
            throw new BusinessException("PLAN_UNKNOWN", "That plan does not exist or is not active.");
        }
        requireTenant(tenantId);
        String cycle = text(body.get("billingCycle")).toUpperCase();
        if (!List.of("NONE", "MONTHLY", "YEARLY").contains(cycle)) {
            cycle = "NONE";
        }
        Instant expiresAt = null;
        Object rawExpiry = body.get("expiresAt");
        if (rawExpiry != null && !rawExpiry.toString().isBlank()) {
            try {
                String v = rawExpiry.toString();
                expiresAt = v.length() == 10 ? Instant.parse(v + "T23:59:59Z") : Instant.parse(v);
            } catch (RuntimeException e) {
                throw new BusinessException("PLAN_EXPIRY", "Expiry date is not valid.");
            }
        } else if (plan.get("durationDays") instanceof Number days) {
            expiresAt = Instant.now().plus(days.longValue(), ChronoUnit.DAYS);
        }
        BigDecimal price = "YEARLY".equals(cycle) ? (BigDecimal) plan.get("priceYearly")
                : "MONTHLY".equals(cycle) ? (BigDecimal) plan.get("priceMonthly") : null;

        db.update("UPDATE school_subscriptions SET status = 'SUPERSEDED' WHERE tenant_id = :t AND status = 'ACTIVE'",
                Db.p("t", tenantId));
        db.update("""
                INSERT INTO school_subscriptions (tenant_id, plan_id, status, billing_cycle, price_snapshot, currency,
                    expires_at, payment_provider, external_reference, notes, created_by)
                VALUES (:t, :plan, 'ACTIVE', :cycle, :price, :currency, :expiresAt, :provider, :reference, :notes, :by)
                """, Db.p("t", tenantId, "plan", plan.get("id"), "cycle", cycle, "price", price,
                "currency", plan.get("currency"),
                "expiresAt", expiresAt == null ? null : java.sql.Timestamp.from(expiresAt),
                "provider", blankToNull(body.get("paymentProvider")),
                "reference", blankToNull(body.get("externalReference")),
                "notes", blankToNull(body.get("notes")), "by", SaasAccess.userId()));
        accessGuard.evictTenant(tenantId);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("plan", code);
        details.put("billingCycle", cycle);
        auditService.record(tenantId, SaasAccess.userId(), null, "PLATFORM_OWNER", "SCHOOL_PLAN_CHANGED",
                "Tenant", tenantId.toString(), details);
        return limitService.summary(tenantId);
    }

    /**
     * Per-school limit overrides. A null value means "use the plan". Lowering a
     * limit below current usage is allowed and never deletes anything.
     */
    @Transactional
    public Map<String, Object> setLimits(UUID tenantId, Map<String, Object> body) {
        SaasAccess.requirePlatformOwner();
        requireTenant(tenantId);
        Integer users = optionalInt(body.get("maxActiveUsers"), 0, 1_000_000, "Active users");
        Integer storage = optionalInt(body.get("storageLimitMb"), 0, 10_000_000, "Storage");
        Integer ram = optionalInt(body.get("ramBudgetMb"), 0, 1_000_000, "RAM budget");
        Integer branches = optionalInt(body.get("maxBranches"), 0, 10_000, "Branches");
        String note = blankToNull(body.get("note"));
        if (users == null && storage == null && ram == null && branches == null) {
            db.update("DELETE FROM tenant_resource_limits WHERE tenant_id = :t", Db.p("t", tenantId));
        } else {
            db.update("""
                    INSERT INTO tenant_resource_limits (tenant_id, max_active_users, storage_limit_mb, ram_budget_mb,
                        max_branches, note, updated_by, updated_at)
                    VALUES (:t, :users, :storage, :ram, :branches, :note, :by, NOW())
                    ON CONFLICT (tenant_id) DO UPDATE SET max_active_users = EXCLUDED.max_active_users,
                        storage_limit_mb = EXCLUDED.storage_limit_mb, ram_budget_mb = EXCLUDED.ram_budget_mb,
                        max_branches = EXCLUDED.max_branches, note = EXCLUDED.note,
                        updated_by = EXCLUDED.updated_by, updated_at = NOW()
                    """, Db.p("t", tenantId, "users", users, "storage", storage, "ram", ram,
                    "branches", branches, "note", note, "by", SaasAccess.userId()));
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("maxActiveUsers", users);
        details.put("storageLimitMb", storage);
        details.put("ramBudgetMb", ram);
        details.put("maxBranches", branches);
        auditService.record(tenantId, SaasAccess.userId(), null, "PLATFORM_OWNER", "SCHOOL_LIMITS_CHANGED",
                "Tenant", tenantId.toString(), details);
        return limitService.summary(tenantId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> history(UUID tenantId) {
        return db.list("""
                SELECT s.id AS "id", p.code AS "planCode", p.name AS "planName", s.status AS "status",
                       s.billing_cycle AS "billingCycle", s.price_snapshot AS "price", s.currency AS "currency",
                       s.started_at AS "startedAt", s.expires_at AS "expiresAt",
                       s.payment_provider AS "paymentProvider", s.external_reference AS "externalReference",
                       s.notes AS "notes"
                FROM school_subscriptions s JOIN subscription_plans p ON p.id = s.plan_id
                WHERE s.tenant_id = :t ORDER BY s.started_at DESC LIMIT 50
                """, Db.p("t", tenantId));
    }

    private void requireTenant(UUID tenantId) {
        if (db.count("SELECT COUNT(*) FROM tenants WHERE id = :t", Db.p("t", tenantId)) == 0) {
            throw new ResourceNotFoundException("School", tenantId);
        }
    }

    private Map<String, Object> values(Map<String, Object> body, Map<String, Object> current) {
        Map<String, Object> p = new LinkedHashMap<>();
        String name = body.containsKey("name") ? text(body.get("name")) : current == null ? "" : text(current.get("name"));
        if (name.isBlank() || name.length() > 80) {
            throw new BusinessException("PLAN_NAME", "Plan name is required (up to 80 characters).");
        }
        p.put("name", name);
        p.put("description", pick(body, current, "description"));
        p.put("priceMonthly", money(pick(body, current, "priceMonthly")));
        p.put("priceYearly", money(pick(body, current, "priceYearly")));
        Object currency = pick(body, current, "currency");
        p.put("currency", currency == null || currency.toString().isBlank() ? "PKR" : currency.toString().trim().toUpperCase());
        p.put("maxActiveUsers", requiredInt(pick(body, current, "maxActiveUsers"), 1, 1_000_000, "Active users"));
        p.put("storageLimitMb", requiredInt(pick(body, current, "storageLimitMb"), 1, 10_000_000, "Storage (MB)"));
        p.put("ramBudgetMb", requiredInt(pick(body, current, "ramBudgetMb"), 1, 1_000_000, "RAM budget (MB)"));
        p.put("maxBranches", requiredInt(pick(body, current, "maxBranches"), 1, 10_000, "Branches"));
        Object modules = pick(body, current, "modules");
        p.put("modules", modules == null ? null : db.json(modules));
        Object features = pick(body, current, "features");
        p.put("features", db.json(features == null ? List.of() : features));
        String policy = String.valueOf(pick(body, current, "resourcePolicy"));
        p.put("resourcePolicy", "WARN_ONLY".equals(policy) ? "WARN_ONLY" : "BLOCK_NEW");
        p.put("durationDays", optionalInt(pick(body, current, "durationDays"), 1, 36_500, "Duration"));
        p.put("isPublic", bool(pick(body, current, "isPublic"), true));
        p.put("isActive", bool(pick(body, current, "isActive"), true));
        p.put("isDefault", bool(pick(body, current, "isDefault"), false));
        Object sort = pick(body, current, "sortOrder");
        p.put("sortOrder", sort instanceof Number n ? n.intValue() : 100);
        return p;
    }

    private static Object pick(Map<String, Object> body, Map<String, Object> current, String key) {
        if (body.containsKey(key)) {
            return body.get(key);
        }
        return current == null ? null : current.get(key);
    }

    private static boolean bool(Object value, boolean fallback) {
        return value == null ? fallback : Boolean.parseBoolean(value.toString());
    }

    private static BigDecimal money(Object value) {
        if (value == null || value.toString().isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            BigDecimal v = new BigDecimal(value.toString().trim());
            if (v.signum() < 0 || v.compareTo(new BigDecimal("9999999999")) > 0) {
                throw new NumberFormatException();
            }
            return v;
        } catch (NumberFormatException e) {
            throw new BusinessException("PLAN_PRICE", "Prices must be zero or a positive number.");
        }
    }

    private static int requiredInt(Object value, int min, int max, String label) {
        Integer v = optionalInt(value, min, max, label);
        if (v == null) {
            throw new BusinessException("PLAN_LIMIT", label + " is required.");
        }
        return v;
    }

    private static Integer optionalInt(Object value, int min, int max, String label) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            long v = value instanceof Number n ? n.longValue() : Long.parseLong(value.toString().trim());
            if (v < min || v > max) {
                throw new NumberFormatException();
            }
            return (int) v;
        } catch (NumberFormatException e) {
            throw new BusinessException("PLAN_LIMIT", label + " must be a whole number between " + min + " and " + max + ".");
        }
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static String blankToNull(Object value) {
        String t = text(value);
        return t.isEmpty() ? null : t;
    }
}
