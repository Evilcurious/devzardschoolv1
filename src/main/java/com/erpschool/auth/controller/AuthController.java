package com.erpschool.auth.controller;

import com.erpschool.auth.dto.AuthResponse;
import com.erpschool.auth.dto.ChangePasswordRequest;
import com.erpschool.auth.dto.LoginRequest;
import com.erpschool.auth.dto.LogoutRequest;
import com.erpschool.auth.dto.RefreshRequest;
import com.erpschool.auth.service.AuthService;
import com.erpschool.common.dto.ApiResponse;
import com.erpschool.security.CurrentUser;
import com.erpschool.user.dto.UserResponse;
import com.erpschool.user.entity.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication")
public class AuthController {

    private final AuthService authService;
    private final com.erpschool.saas.security.FileAccessService fileAccess;

    public AuthController(AuthService authService, com.erpschool.saas.security.FileAccessService fileAccess) {
        this.authService = authService;
        this.fileAccess = fileAccess;
    }

    /** Sets (or clears) the HttpOnly cookie that lets this browser read the session school's files. */
    private AuthResponse withFileAccess(AuthResponse auth, HttpServletRequest http,
                                        jakarta.servlet.http.HttpServletResponse response) {
        if (auth.getAccessToken() != null) {
            fileAccess.grant(http, response, auth.getTenant() == null ? null : auth.getTenant().getId());
        }
        return auth;
    }

    @PostMapping("/login")
    @Operation(summary = "Login with username and password")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request,
                                                           HttpServletRequest http,
                                                           jakarta.servlet.http.HttpServletResponse response) {
        return ResponseEntity.ok(ApiResponse.ok("Login successful",
                withFileAccess(authService.login(request.getUsername(), request.getPassword(), http), http, response)));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Issue a new access token using a refresh token")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(@Valid @RequestBody RefreshRequest request,
                                                             HttpServletRequest http,
                                                             jakarta.servlet.http.HttpServletResponse response) {
        return ResponseEntity.ok(ApiResponse.ok(
                withFileAccess(authService.refresh(request.getRefreshToken(), http), http, response)));
    }

    @PostMapping("/select")
    @Operation(summary = "Central login step 2: enter one of the authorised schools / branches")
    public ResponseEntity<ApiResponse<AuthResponse>> select(@Valid @RequestBody SelectRequest request,
                                                            HttpServletRequest http,
                                                            jakarta.servlet.http.HttpServletResponse response) {
        return ResponseEntity.ok(ApiResponse.ok("Login successful",
                withFileAccess(authService.select(request.getSelectionToken(), request.getTenantId(),
                        request.getBranchId(), request.getBranchPassword(), http), http, response)));
    }

    @PostMapping("/switch")
    @Operation(summary = "Switch to another authorised school or branch")
    public ResponseEntity<ApiResponse<AuthResponse>> switchContext(@Valid @RequestBody SwitchRequest request,
                                                                   HttpServletRequest http,
                                                                   jakarta.servlet.http.HttpServletResponse response) {
        return ResponseEntity.ok(ApiResponse.ok("Switched",
                withFileAccess(authService.switchContext(CurrentUser.require().getId(), request.getTenantId(),
                        request.getBranchId(), request.getBranchPassword(), http), http, response)));
    }

    @GetMapping("/contexts")
    @Operation(summary = "Schools and branches the signed-in user is authorised for")
    public ResponseEntity<ApiResponse<java.util.List<com.erpschool.saas.access.SchoolOption>>> contexts() {
        return ResponseEntity.ok(ApiResponse.ok(authService.contexts(CurrentUser.require().getId())));
    }

    @lombok.Getter
    @lombok.Setter
    public static class SwitchRequest {
        @jakarta.validation.constraints.NotNull(message = "School is required")
        private java.util.UUID tenantId;
        private java.util.UUID branchId;
        @jakarta.validation.constraints.Size(max = 200)
        private String branchPassword;
    }

    @lombok.Getter
    @lombok.Setter
    public static class SelectRequest extends SwitchRequest {
        @jakarta.validation.constraints.NotBlank(message = "Selection token is required")
        private String selectionToken;
    }

    @PostMapping("/logout")
    @Operation(summary = "Revoke the given refresh token")
    public ResponseEntity<ApiResponse<Void>> logout(@Valid @RequestBody LogoutRequest request,
                                                    HttpServletRequest http,
                                                    jakarta.servlet.http.HttpServletResponse response) {
        authService.logout(request.getRefreshToken());
        fileAccess.clear(http, response);
        return ResponseEntity.ok(ApiResponse.ok("Logged out", null));
    }

    @PostMapping("/change-password")
    @Operation(summary = "Change username and/or password for the authenticated user")
    public ResponseEntity<ApiResponse<UserResponse>> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        User user = authService.requireUser(CurrentUser.require().getId());
        UserResponse updated = authService.changeCredentials(
                user, request.getCurrentPassword(), request.getNewPassword(), request.getNewUsername());
        return ResponseEntity.ok(ApiResponse.ok("Credentials updated. Please login again.", updated));
    }

    @GetMapping("/me")
    @Operation(summary = "Current authenticated user")
    public ResponseEntity<ApiResponse<UserResponse>> me() {
        User user = authService.requireUser(CurrentUser.require().getId());
        return ResponseEntity.ok(ApiResponse.ok(authService.me(user)));
    }
}
