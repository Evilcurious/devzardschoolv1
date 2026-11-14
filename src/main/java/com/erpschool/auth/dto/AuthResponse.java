package com.erpschool.auth.dto;

import com.erpschool.tenant.dto.TenantResponse;
import com.erpschool.user.dto.UserResponse;
import lombok.Builder;
import lombok.Getter;

import java.util.Map;

@Getter
@Builder(toBuilder = true)
public class AuthResponse {

    private final String accessToken;
    private final String refreshToken;
    private final String tokenType;
    private final long expiresIn;
    private final UserResponse user;
    private final TenantResponse tenant;
    private final Map<String, Boolean> features;

    /** Server-resolved session context: platform role, school, branch. */
    private final Map<String, Object> context;

    /**
     * True when the password was correct but the account can enter more than
     * one school or branch. No tokens are issued; the client shows the private
     * picker built from {@link #schools} and calls /auth/select.
     */
    private final Boolean selectionRequired;
    private final String selectionToken;
    private final java.util.List<com.erpschool.saas.access.SchoolOption> schools;
}
