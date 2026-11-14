package com.erpschool.tenant.context;

import com.erpschool.user.entity.UserRole;

import java.util.UUID;

/**
 * Branch isolation for everything inside a school.
 *
 * <p>The branch of a session is decided on the server at sign-in (see
 * {@code AccessContextService}) and travels in the access token. When a session
 * has a branch, every role is limited to it, including a School Owner who chose
 * to work inside one branch. A session without a branch belongs to a school-wide
 * role and sees every branch. The tenant filter is never disabled.</p>
 */
public final class CampusScope {

    private CampusScope() {
    }

    public static UUID current() {
        return TenantContext.getCampusId();
    }

    public static boolean restricts() {
        UserRole role = TenantContext.getRole();
        if (role == null || role == UserRole.ERP_OWNER) {
            return false;
        }
        if (role == UserRole.PARENT || role == UserRole.STUDENT) {
            // Portal users are confined to their own records by identity, not by branch
            // (a parent may have children in two branches of the same school).
            return false;
        }
        return TenantContext.getCampusId() != null;
    }
}
