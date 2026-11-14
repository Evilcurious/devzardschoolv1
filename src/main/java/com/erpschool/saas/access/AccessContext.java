package com.erpschool.saas.access;

import com.erpschool.saas.SaasRole;
import com.erpschool.user.entity.UserRole;

import java.util.UUID;

/**
 * A session context the server has verified: this user, in this school, in
 * this branch (or every branch when {@code campusId} is null), with this role.
 */
public record AccessContext(UUID tenantId,
                            String tenantName,
                            UUID campusId,
                            String campusName,
                            SaasRole role,
                            UserRole erpRole,
                            String verificationStatus,
                            boolean canSwitch) {

    public boolean allBranches() {
        return campusId == null;
    }
}
