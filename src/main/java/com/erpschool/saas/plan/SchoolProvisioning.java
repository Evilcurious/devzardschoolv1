package com.erpschool.saas.plan;

import com.erpschool.saas.support.Db;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Plan assignment for schools created by trusted, non-public code paths
 * (the demo seed and the optional first-time setup). Schools that register
 * themselves always start on the platform default plan, which the database
 * assigns when the school row is created.
 */
@Component
public class SchoolProvisioning {

    private final Db db;

    public SchoolProvisioning(Db db) {
        this.db = db;
    }

    /** Replace the school's current plan with {@code planCode}. Does nothing if the plan does not exist. */
    @Transactional
    public void usePlan(UUID tenantId, String planCode, String note) {
        Long planExists = db.count("SELECT COUNT(*) FROM subscription_plans WHERE code = :c AND is_active",
                Db.p("c", planCode));
        if (planExists == 0) {
            return;
        }
        if (db.count("""
                SELECT COUNT(*) FROM school_subscriptions s JOIN subscription_plans p ON p.id = s.plan_id
                WHERE s.tenant_id = :t AND s.status = 'ACTIVE' AND p.code = :c
                """, Db.p("t", tenantId, "c", planCode)) > 0) {
            return;
        }
        db.update("UPDATE school_subscriptions SET status = 'SUPERSEDED' WHERE tenant_id = :t AND status = 'ACTIVE'",
                Db.p("t", tenantId));
        db.update("""
                INSERT INTO school_subscriptions (tenant_id, plan_id, status, notes)
                SELECT :t, p.id, 'ACTIVE', :note FROM subscription_plans p WHERE p.code = :c
                """, Db.p("t", tenantId, "c", planCode, "note", note));
    }
}
