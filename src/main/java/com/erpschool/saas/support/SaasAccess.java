package com.erpschool.saas.support;

import com.erpschool.common.exception.ForbiddenException;
import com.erpschool.common.exception.UnauthorizedException;
import com.erpschool.saas.SaasRole;
import com.erpschool.tenant.context.TenantContext;
import com.erpschool.user.entity.UserRole;

import java.util.UUID;

/** Server-side authorisation checks for the platform layer. */
public final class SaasAccess {

    private SaasAccess() {
    }

    public static UUID userId() {
        UUID id = TenantContext.getUserId();
        if (id == null) {
            throw new UnauthorizedException("Sign in to continue.");
        }
        return id;
    }

    public static void requirePlatformOwner() {
        if (TenantContext.getRole() != UserRole.ERP_OWNER) {
            throw new ForbiddenException("Only the platform owner can do this.");
        }
    }

    /** The session's school. A school user can never name another school. */
    public static UUID tenantId() {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null || TenantContext.getRole() == UserRole.ERP_OWNER) {
            throw new ForbiddenException("This action belongs to a school account.");
        }
        return tenantId;
    }

    public static UUID requireSchoolOwner() {
        UUID tenantId = tenantId();
        if (TenantContext.getSaasRole() != SaasRole.SCHOOL_OWNER) {
            throw new ForbiddenException("Only the school owner can do this.");
        }
        return tenantId;
    }

    /** School Owner or School Admin (not a branch-level role). */
    public static UUID requireSchoolWide() {
        UUID tenantId = tenantId();
        SaasRole role = TenantContext.getSaasRole();
        if (role == null || !role.schoolWide()) {
            throw new ForbiddenException("Only the school owner or a school admin can do this.");
        }
        return tenantId;
    }

    /** Owner, school admin, or the admin of a branch. */
    public static UUID requireManager() {
        UUID tenantId = tenantId();
        SaasRole role = TenantContext.getSaasRole();
        if (role == null || !(role.schoolWide() || role == SaasRole.BRANCH_ADMIN)) {
            throw new ForbiddenException("You do not have permission to do this.");
        }
        return tenantId;
    }
}
