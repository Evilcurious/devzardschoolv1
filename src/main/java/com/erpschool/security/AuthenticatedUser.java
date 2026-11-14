package com.erpschool.security;

import com.erpschool.user.entity.UserRole;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public class AuthenticatedUser implements UserDetails {

    private final UUID id;
    private final UUID tenantId;
    private final String username;
    private final String passwordHash;
    private final UserRole role;
    private final boolean active;
    private final boolean locked;
    private final com.erpschool.saas.SaasRole saasRole;

    public AuthenticatedUser(UUID id,
                             UUID tenantId,
                             String username,
                             String passwordHash,
                             UserRole role,
                             boolean active,
                             boolean locked) {
        this(id, tenantId, username, passwordHash, role, active, locked, null);
    }

    public AuthenticatedUser(UUID id,
                             UUID tenantId,
                             String username,
                             String passwordHash,
                             UserRole role,
                             boolean active,
                             boolean locked,
                             com.erpschool.saas.SaasRole saasRole) {
        this.saasRole = saasRole;
        this.id = id;
        this.tenantId = tenantId;
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.active = active;
        this.locked = locked;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public com.erpschool.saas.SaasRole getSaasRole() {
        return saasRole;
    }

    public UserRole getRole() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        if (saasRole == null || saasRole.name().equals(role.name())) {
            return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
        }
        // ERP permission profile + platform role, e.g. ROLE_SCHOOL_ADMIN + ROLE_SCHOOL_OWNER.
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()),
                new SimpleGrantedAuthority("ROLE_" + saasRole.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return !locked;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return active;
    }
}
