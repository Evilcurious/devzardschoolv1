package com.erpschool.auth.service;

import com.erpschool.audit.service.AuditService;
import com.erpschool.saas.SaasRole;
import com.erpschool.saas.access.AccessContext;
import com.erpschool.saas.access.AccessContextService;
import com.erpschool.auth.dto.AuthResponse;
import com.erpschool.auth.entity.RefreshToken;
import com.erpschool.auth.repository.RefreshTokenRepository;
import com.erpschool.common.exception.BusinessException;
import com.erpschool.common.exception.UnauthorizedException;
import com.erpschool.common.util.HttpRequestUtils;
import com.erpschool.common.util.TokenHasher;
import com.erpschool.config.AppProperties;
import com.erpschool.feature.FeatureService;
import com.erpschool.security.JwtService;
import com.erpschool.tenant.dto.TenantResponse;
import com.erpschool.common.util.StatusCopy;
import com.erpschool.student.entity.Student;
import com.erpschool.student.repository.StudentRepository;
import com.erpschool.tenant.entity.Tenant;
import com.erpschool.tenant.repository.TenantRepository;
import com.erpschool.user.dto.UserResponse;
import com.erpschool.user.entity.User;
import com.erpschool.user.entity.UserRole;
import com.erpschool.user.entity.UserStatus;
import com.erpschool.user.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Service
public class AuthService {

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int LOCK_MINUTES = 15;
    private static final Executor LOGIN_FOLLOW_UP = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "auth-login-followup");
        t.setDaemon(true);
        return t;
    });

    private final UserRepository userRepository;
    private final StudentRepository studentRepository;
    private final TenantRepository tenantRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AppProperties appProperties;
    private final AuditService auditService;
    private final FeatureService featureService;
    private final AccessContextService accessContextService;
    private final TransactionTemplate readTx;
    private final TransactionTemplate writeTx;

    public AuthService(UserRepository userRepository,
                       StudentRepository studentRepository,
                       TenantRepository tenantRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       AppProperties appProperties,
                       AuditService auditService,
                       FeatureService featureService,
                       AccessContextService accessContextService,
                       PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.studentRepository = studentRepository;
        this.tenantRepository = tenantRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.appProperties = appProperties;
        this.auditService = auditService;
        this.featureService = featureService;
        this.accessContextService = accessContextService;
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
        this.writeTx = new TransactionTemplate(transactionManager);
    }

    /**
     * BCrypt runs outside the write transaction so Neon/PgBouncer is not left
     * idle-in-transaction. User+tenant load share one read connection; last login,
     * refresh token, and audit share one write connection (no extra Hikari checkout).
     */
    public AuthResponse login(String username, String password, HttpServletRequest request) {
        String identifier = username == null ? "" : username.trim();
        LoginRow loaded = readTx.execute(status -> {
            User found = null;
            if (!identifier.isEmpty()) {
                java.util.List<User> matches = userRepository.findLoginCandidates(identifier);
                if (matches != null && !matches.isEmpty()) {
                    found = matches.get(0);
                }
            }
            if (found == null) {
                return new LoginRow(null, null);
            }
            Tenant school = null;
            if (found.getRole() != UserRole.ERP_OWNER && found.getTenantId() != null) {
                school = tenantRepository.findById(found.getTenantId()).orElse(null);
            }
            return new LoginRow(found, school);
        });
        User user = loaded == null ? null : loaded.user();
        if (user == null) {
            writeTx.executeWithoutResult(status -> auditService.recordSameTransaction(
                    null, null, identifier, null, AuditService.LOGIN_FAILED,
                    "User", null, Map.of("reason", "unknown_user")));
            throw new UnauthorizedException("Invalid username or password");
        }
        if (user.getStatus() == UserStatus.LOCKED) {
            if (user.getLockedUntil() != null && user.getLockedUntil().isBefore(Instant.now())) {
                user.setStatus(UserStatus.ACTIVE);
                user.setLockedUntil(null);
                user.setFailedLoginAttempts(0);
            } else {
                throw new UnauthorizedException("Account is locked. Try again later or contact administration.");
            }
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new UnauthorizedException(inactiveLoginMessage(user));
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            writeTx.executeWithoutResult(status -> {
                registerFailure(user);
                auditService.recordSameTransaction(user.getTenantId(), user.getId(), user.getUsername(),
                        user.getRole().name(), AuditService.LOGIN_FAILED, "User", user.getId().toString(),
                        Map.of("reason", "bad_password"));
            });
            throw new UnauthorizedException("Invalid username or password");
        }
        Instant now = Instant.now();
        String ip = HttpRequestUtils.clientIp(request);
        String ua = HttpRequestUtils.userAgent(request);
        UUID userId = user.getId();
        String accountUsername = user.getUsername();

        /* Central login: the school and branch are resolved here, on the server,
           from membership records. The client never names a tenant at this step. */
        AccessContext ctx = null;
        Tenant resolvedTenant = null;
        if (user.getRole() != UserRole.ERP_OWNER) {
            AccessContextService.Resolution resolution = accessContextService.resolveLogin(userId);
            if (resolution.selectionRequired()) {
                LOGIN_FOLLOW_UP.execute(() -> {
                    try {
                        writeTx.executeWithoutResult(status -> {
                            userRepository.markSuccessfulLogin(userId, now, UserStatus.ACTIVE);
                            auditService.recordSameTransaction(null, userId, accountUsername, null,
                                    AuditService.LOGIN_SUCCESS, "User", userId.toString(),
                                    Map.of("step", "selection_required"), ip, ua);
                        });
                    } catch (RuntimeException ignored) {
                        /* Selection can still proceed. */
                    }
                });
                return AuthResponse.builder()
                        .selectionRequired(true)
                        .selectionToken(jwtService.createSelectionToken(userId))
                        .schools(resolution.options())
                        .build();
            }
            ctx = resolution.context();
            resolvedTenant = loaded != null && loaded.tenant() != null
                    && ctx.tenantId().equals(loaded.tenant().getId())
                    ? loaded.tenant()
                    : tenantRepository.findById(ctx.tenantId()).orElse(null);
            if (resolvedTenant == null) {
                throw new UnauthorizedException("School account is not available");
            }
            assertTenantAllowsLogin(ctx.erpRole(), resolvedTenant);
        }
        final AccessContext context = ctx;
        final Tenant tenant = resolvedTenant;
        UUID tenantId = context == null ? null : context.tenantId();
        Map<String, Boolean> features = snapshotFeatures(tenantId);
        String role = context == null ? user.getRole().name() : context.role().name();
        /* Tokens first so the HTTP response is not blocked on users.last_login_at
           (the Neon write that previously stalled login for several seconds).
           last_login and LOGIN_SUCCESS audit still run; they just follow the response. */
        AuthResponse response = writeTx.execute(status -> issueTokens(user, request, tenant, features, context));
        if (response == null) {
            throw new UnauthorizedException("Unable to complete login");
        }
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(now);
        user.setStatus(UserStatus.ACTIVE);
        LOGIN_FOLLOW_UP.execute(() -> {
            try {
                writeTx.executeWithoutResult(status -> {
                    userRepository.markSuccessfulLogin(userId, now, UserStatus.ACTIVE);
                    auditService.recordSameTransaction(tenantId, userId, accountUsername, role,
                            AuditService.LOGIN_SUCCESS, "User", userId.toString(), null, ip, ua);
                });
            } catch (RuntimeException ignored) {
                /* Caller already has a valid session. */
            }
        });
        return response;
    }

    @Transactional
    public AuthResponse refresh(String refreshTokenValue, HttpServletRequest request) {
        RefreshToken stored = refreshTokenRepository.findByTokenHash(TokenHasher.sha256(refreshTokenValue))
                .orElseThrow(() -> new UnauthorizedException("Invalid refresh token"));
        if (!stored.isUsable()) {
            throw new UnauthorizedException("Refresh token is expired or revoked");
        }
        User user = userRepository.findById(stored.getUserId())
                .orElseThrow(() -> new UnauthorizedException("Invalid refresh token"));
        if (!user.isActive()) {
            stored.revoke();
            refreshTokenRepository.save(stored);
            throw new UnauthorizedException("Account is not active");
        }
        /* A refresh re-validates the session: the membership, the school and the
           branch must all still be valid, otherwise the user signs in again. */
        AccessContext ctx = null;
        Tenant tenant = null;
        if (user.getRole() != UserRole.ERP_OWNER) {
            UUID tenantId = stored.getTenantId() != null ? stored.getTenantId() : user.getTenantId();
            ctx = accessContextService.revalidate(user.getId(), tenantId, stored.getCampusId());
            tenant = tenantRepository.findById(ctx.tenantId())
                    .orElseThrow(() -> new UnauthorizedException("School account is not available"));
            assertTenantAllowsLogin(ctx.erpRole(), tenant);
        }
        stored.revoke();
        refreshTokenRepository.save(stored);
        return issueTokens(user, request, tenant, snapshotFeatures(ctx == null ? null : ctx.tenantId()), ctx);
    }

    /**
     * Second step of the central login: enter one of the schools / branches the
     * server listed. The selection token only proves the password was checked;
     * the requested school and branch are re-authorised against memberships.
     */
    public AuthResponse select(String selectionToken, UUID tenantId, UUID campusId, String branchPassword,
                               HttpServletRequest request) {
        UUID userId = jwtService.parseSelectionToken(selectionToken);
        if (userId == null) {
            throw new UnauthorizedException("Your sign-in has expired. Please sign in again.");
        }
        return enter(userId, tenantId, campusId, branchPassword, request, "CONTEXT_SELECTED");
    }

    /** Switch school or branch without signing in again. Same authorisation as {@link #select}. */
    public AuthResponse switchContext(UUID userId, UUID tenantId, UUID campusId, String branchPassword,
                                      HttpServletRequest request) {
        return enter(userId, tenantId, campusId, branchPassword, request, "CONTEXT_SWITCHED");
    }

    private AuthResponse enter(UUID userId, UUID tenantId, UUID campusId, String branchPassword,
                               HttpServletRequest request, String auditAction) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("Account is not available"));
        if (!user.isActive() || user.getRole() == UserRole.ERP_OWNER) {
            throw new UnauthorizedException("Account is not available");
        }
        AccessContext ctx = accessContextService.select(userId, tenantId, campusId, branchPassword);
        Tenant tenant = tenantRepository.findById(ctx.tenantId())
                .orElseThrow(() -> new UnauthorizedException("School account is not available"));
        assertTenantAllowsLogin(ctx.erpRole(), tenant);
        Map<String, Boolean> features = snapshotFeatures(ctx.tenantId());
        AuthResponse response = writeTx.execute(status -> issueTokens(user, request, tenant, features, ctx));
        if (response == null) {
            throw new UnauthorizedException("Unable to complete sign-in");
        }
        Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("school", ctx.tenantName());
        details.put("branch", ctx.campusName() == null ? "All branches" : ctx.campusName());
        auditService.record(ctx.tenantId(), userId, user.getUsername(), ctx.role().name(),
                auditAction, "User", userId.toString(), details);
        return response;
    }

    /** The caller's authorised schools and branches, for the in-app switcher. */
    public java.util.List<com.erpschool.saas.access.SchoolOption> contexts(UUID userId) {
        return accessContextService.options(userId);
    }

    @Transactional
    public void logout(String refreshTokenValue) {
        refreshTokenRepository.findByTokenHash(TokenHasher.sha256(refreshTokenValue))
                .ifPresent(token -> {
                    token.revoke();
                    refreshTokenRepository.save(token);
                });
        auditService.record(AuditService.LOGOUT, "User", null, null);
    }

    public UserResponse changePassword(User actor, String currentPassword, String newPassword) {
        return changeCredentials(actor, currentPassword, newPassword, null);
    }

    /**
     * Verify the current password and encode any new hash outside the write
     * transaction so Neon is not left idle-in-transaction during BCrypt.
     * Username-only, password-only, or both are persisted with saveAndFlush.
     */
    public UserResponse changeCredentials(User actor, String currentPassword, String newPassword, String newUsername) {
        User loaded = userRepository.findById(actor.getId())
                .orElseThrow(() -> new UnauthorizedException("User not found"));
        if (!passwordEncoder.matches(currentPassword, loaded.getPasswordHash())) {
            throw new UnauthorizedException("Current password is incorrect");
        }
        String trimmedUsername = newUsername == null ? null : newUsername.trim();
        boolean usernameChange = trimmedUsername != null && !trimmedUsername.isEmpty()
                && !trimmedUsername.equalsIgnoreCase(loaded.getUsername());
        boolean passwordChange = newPassword != null && !newPassword.isBlank();
        if (!usernameChange && !passwordChange) {
            throw new BusinessException("Provide a new username and/or a new password");
        }
        if (usernameChange) {
            if (trimmedUsername.length() < 3 || trimmedUsername.length() > 80) {
                throw new BusinessException("Username must be between 3 and 80 characters");
            }
        }
        if (passwordChange) {
            if (currentPassword.equals(newPassword)) {
                throw new BusinessException("NEW_PASSWORD_SAME", "New password must be different from the current password");
            }
            if (newPassword.length() < 8 || newPassword.length() > 72
                    || !newPassword.matches("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).+$")) {
                throw new BusinessException("Password must be 8–72 characters with upper, lower, digit and a special character");
            }
        }
        String encoded = passwordChange ? passwordEncoder.encode(newPassword) : null;
        UserResponse updated = writeTx.execute(status -> {
            User user = userRepository.findById(actor.getId())
                    .orElseThrow(() -> new UnauthorizedException("User not found"));
            if (usernameChange) {
                UUID currentUserId = user.getId();
                userRepository.findByUsernameIgnoreCase(trimmedUsername)
                        .filter(existing -> !existing.getId().equals(currentUserId))
                        .ifPresent(existing -> {
                            throw new BusinessException("Username is already in use");
                        });
                user.setUsername(trimmedUsername);
            }
            if (passwordChange) {
                user.setPasswordHash(encoded);
                user.setMustChangePassword(false);
                user.setPasswordChangedAt(Instant.now());
            }
            refreshTokenRepository.revokeAllForUser(user.getId(), Instant.now());
            return UserResponse.from(userRepository.saveAndFlush(user));
        });
        if (updated == null) {
            throw new BusinessException("Unable to save credentials");
        }
        auditService.record(AuditService.PASSWORD_CHANGED, "User", actor.getId().toString(),
                Map.of("usernameChanged", usernameChange, "passwordChanged", passwordChange));
        return updated;
    }

    @Transactional(readOnly = true)
    public UserResponse me(User actor) {
        User user = userRepository.findById(actor.getId())
                .orElseThrow(() -> new UnauthorizedException("User not found"));
        UserRole sessionRole = com.erpschool.tenant.context.TenantContext.getRole();
        if (sessionRole == null || sessionRole == UserRole.ERP_OWNER || user.getRole() == UserRole.ERP_OWNER) {
            return UserResponse.from(user);
        }
        // Report the school / branch / role of this session, not the account's home columns.
        return UserResponse.from(user, com.erpschool.tenant.context.TenantContext.getTenantId(),
                com.erpschool.tenant.context.TenantContext.getCampusId(), sessionRole);
    }

    public User requireUser(java.util.UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("User not found"));
    }

    private AuthResponse issueTokens(User user, HttpServletRequest request, Tenant tenant,
                                    Map<String, Boolean> features, AccessContext ctx) {
        String accessToken = ctx == null
                ? jwtService.createAccessToken(user.getId(), user.getUsername(), UserRole.ERP_OWNER,
                        SaasRole.PLATFORM_OWNER, null, null)
                : jwtService.createAccessToken(user.getId(), user.getUsername(), ctx.erpRole(),
                        ctx.role(), ctx.tenantId(), ctx.campusId());
        String refreshToken = TokenHasher.randomToken();

        RefreshToken entity = new RefreshToken();
        entity.setUserId(user.getId());
        entity.setTenantId(ctx == null ? null : ctx.tenantId());
        entity.setCampusId(ctx == null ? null : ctx.campusId());
        entity.setTokenHash(TokenHasher.sha256(refreshToken));
        entity.setExpiresAt(Instant.now().plusSeconds(appProperties.getJwt().getRefreshTokenTtlSeconds()));
        entity.setUserAgent(HttpRequestUtils.userAgent(request));
        entity.setIpAddress(HttpRequestUtils.clientIp(request));
        refreshTokenRepository.save(entity);

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(jwtService.getAccessTtlSeconds())
                .user(ctx == null ? UserResponse.from(user)
                        : UserResponse.from(user, ctx.tenantId(), ctx.campusId(), ctx.erpRole()))
                .context(ctx == null ? AccessContextService.describePlatformOwner()
                        : AccessContextService.describe(ctx))
                .tenant(tenant == null ? null : TenantResponse.from(tenant))
                .features(features == null || features.isEmpty() ? null : features)
                .build();
    }

    private Map<String, Boolean> snapshotFeatures(UUID tenantId) {
        if (tenantId == null) {
            return Map.of();
        }
        try {
            return featureService.snapshotIfCached(tenantId);
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    private void registerFailure(User user) {
        User persistent = userRepository.findById(user.getId()).orElse(user);
        int attempts = persistent.getFailedLoginAttempts() + 1;
        persistent.setFailedLoginAttempts(attempts);
        if (attempts >= MAX_FAILED_ATTEMPTS) {
            persistent.setStatus(UserStatus.LOCKED);
            persistent.setLockedUntil(Instant.now().plus(LOCK_MINUTES, ChronoUnit.MINUTES));
        }
        userRepository.saveAndFlush(persistent);
        user.setFailedLoginAttempts(persistent.getFailedLoginAttempts());
        user.setStatus(persistent.getStatus());
        user.setLockedUntil(persistent.getLockedUntil());
    }

    private void assertTenantAllowsLogin(UserRole erpRole, Tenant tenant) {
        if (erpRole == UserRole.ERP_OWNER) {
            return;
        }
        if (tenant == null) {
            throw new UnauthorizedException("School account is not available");
        }
        if (tenant.allowsSchoolLogin()) {
            return;
        }
        /* School admin may authenticate while the tenant is locked so they can
         * submit subscription payment proof. Module APIs stay blocked by
         * SubscriptionGuardFilter. Other school roles cannot log in. */
        if (erpRole == UserRole.SCHOOL_ADMIN) {
            return;
        }
        throw new UnauthorizedException(
                "Your school's ERP subscription is currently inactive. Please complete the payment and submit the payment proof to reactivate your account.");
    }

    private String inactiveLoginMessage(User user) {
        if (user.getRole() != UserRole.STUDENT || user.getTenantId() == null) {
            return "Your account is currently inactive.";
        }
        Student student = readTx.execute(status ->
                studentRepository.findByTenantIdAndUserId(user.getTenantId(), user.getId()).orElse(null));
        if (student == null) {
            return "Your account is currently inactive.";
        }
        return StatusCopy.loginInactiveMessage(student.getInactiveReason(), student.getInactiveNote());
    }

    private record LoginRow(User user, Tenant tenant) {
    }
}
