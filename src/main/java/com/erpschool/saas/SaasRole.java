package com.erpschool.saas;

import com.erpschool.user.entity.UserRole;

/**
 * Role of an account on the DEVZARD eSchool platform.
 *
 * <p>The existing ERP modules authorise with {@link UserRole}. Rather than
 * rewrite ~150 authorisation rules, every SaaS role carries the ERP permission
 * profile it uses inside a school; the SaaS role itself is added to the
 * request as an extra authority and guards the new platform features.</p>
 */
public enum SaasRole {
    PLATFORM_OWNER(UserRole.ERP_OWNER),
    SCHOOL_OWNER(UserRole.SCHOOL_ADMIN),
    SCHOOL_ADMIN(UserRole.SCHOOL_ADMIN),
    BRANCH_ADMIN(UserRole.SCHOOL_ADMIN),
    TEACHER(UserRole.TEACHER),
    STAFF(UserRole.ACCOUNT_OFFICER),
    STUDENT(UserRole.STUDENT),
    PARENT(UserRole.PARENT);

    private final UserRole defaultErpRole;

    SaasRole(UserRole defaultErpRole) {
        this.defaultErpRole = defaultErpRole;
    }

    public UserRole defaultErpRole() {
        return defaultErpRole;
    }

    /** May this role work across every branch of its school? */
    public boolean schoolWide() {
        return this == SCHOOL_OWNER || this == SCHOOL_ADMIN;
    }

    /** SaaS role implied by an ERP role when no membership row says otherwise. */
    public static SaasRole fromErpRole(UserRole role, boolean hasBranch) {
        if (role == null) {
            return null;
        }
        return switch (role) {
            case ERP_OWNER -> PLATFORM_OWNER;
            case SCHOOL_ADMIN -> hasBranch ? BRANCH_ADMIN : SCHOOL_ADMIN;
            case PRINCIPAL -> SCHOOL_ADMIN;
            case ACCOUNT_OFFICER -> STAFF;
            case TEACHER -> TEACHER;
            case PARENT -> PARENT;
            case STUDENT -> STUDENT;
        };
    }

    public static SaasRole parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
