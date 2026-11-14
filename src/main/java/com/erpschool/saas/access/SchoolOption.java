package com.erpschool.saas.access;

import com.erpschool.saas.SaasRole;
import com.erpschool.user.entity.UserRole;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;
import java.util.UUID;

/** One school the signed-in user may enter, with the branches they may enter in it. */
public record SchoolOption(UUID tenantId,
                           String name,
                           String logoUrl,
                           SaasRole role,
                           @JsonIgnore UserRole erpRole,
                           String status,
                           String verificationStatus,
                           boolean allBranches,
                           List<BranchOption> branches,
                           @JsonIgnore String blockedReason) {

    public record BranchOption(UUID id, String name, boolean main, boolean requiresPassword) {
    }

    /** Number of distinct contexts this school offers the user. */
    @JsonIgnore
    public int choices() {
        return (allBranches ? 1 : 0) + branches.size();
    }
}
