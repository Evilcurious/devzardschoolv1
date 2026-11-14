package com.erpschool.campus.service;

import com.erpschool.audit.service.AuditService;
import com.erpschool.campus.entity.Campus;
import com.erpschool.campus.repository.CampusRepository;
import com.erpschool.common.cache.CatalogCache;
import com.erpschool.common.exception.DuplicateResourceException;
import com.erpschool.common.exception.ResourceNotFoundException;
import com.erpschool.common.util.TenantGuard;
import com.erpschool.tenant.context.CampusScope;
import com.erpschool.tenant.context.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CampusService {

    private final CampusRepository campusRepository;
    private final AuditService auditService;
    private final CatalogCache catalogCache;
    private final com.erpschool.saas.limits.LimitService limitService;

    public CampusService(CampusRepository campusRepository, AuditService auditService, CatalogCache catalogCache,
                         com.erpschool.saas.limits.LimitService limitService) {
        this.limitService = limitService;
        this.campusRepository = campusRepository;
        this.auditService = auditService;
        this.catalogCache = catalogCache;
    }

    @Transactional
    public Campus create(Campus incoming) {
        UUID tenantId = TenantGuard.requireTenantId(null);
        requireSchoolWide("Only the school owner can add a branch.");
        limitService.assertBranchSlot(tenantId);
        if (campusRepository.existsByTenantIdAndCodeIgnoreCase(tenantId, incoming.getCode())) {
            throw new DuplicateResourceException("Campus code already exists");
        }
        incoming.setTenantId(tenantId);
        incoming.setCode(incoming.getCode().trim().toUpperCase());
        incoming.setCreatedBy(TenantContext.getUserId());
        Campus saved = campusRepository.save(incoming);
        catalogCache.evictTenant(tenantId);
        auditService.record("CAMPUS_CREATED", "Campus", saved.getId().toString(), Map.of("code", saved.getCode()));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Campus> list() {
        UUID branch = CampusScope.restricts() ? CampusScope.current() : null;
        // A branch session sees its own branch only; archived branches are never listed here.
        return campusRepository.findByTenantIdOrderByNameAsc(TenantGuard.requireTenantId(null)).stream()
                .filter(c -> !"ARCHIVED".equals(c.getStatus()))
                .filter(c -> branch == null || branch.equals(c.getId()))
                .toList();
    }

    private static void requireSchoolWide(String message) {
        if (TenantContext.getRole() == com.erpschool.user.entity.UserRole.ERP_OWNER) {
            return;
        }
        com.erpschool.saas.SaasRole role = TenantContext.getSaasRole();
        if (role == null || !role.schoolWide() || TenantContext.getCampusId() != null) {
            throw new com.erpschool.common.exception.ForbiddenException(message);
        }
    }

    @Transactional
    public Campus update(UUID id, Campus incoming) {
        Campus campus = get(id);
        if (incoming.getName() != null) campus.setName(incoming.getName());
        if (incoming.getAddress() != null) campus.setAddress(incoming.getAddress());
        if (incoming.getCity() != null) campus.setCity(incoming.getCity());
        if (incoming.getPhone() != null) campus.setPhone(incoming.getPhone());
        if (incoming.getEmail() != null) campus.setEmail(incoming.getEmail());
        if (incoming.getFacebookUrl() != null) campus.setFacebookUrl(incoming.getFacebookUrl());
        if (incoming.getInstagramUrl() != null) campus.setInstagramUrl(incoming.getInstagramUrl());
        if (incoming.getYoutubeUrl() != null) campus.setYoutubeUrl(incoming.getYoutubeUrl());
        if (incoming.getTwitterUrl() != null) campus.setTwitterUrl(incoming.getTwitterUrl());
        if (incoming.getHeroJson() != null) campus.setHeroJson(incoming.getHeroJson());
        if (incoming.isWebsiteGeoUpdate()) {
            campus.setMapLat(incoming.getMapLat());
            campus.setMapLng(incoming.getMapLng());
            campus.setMapPlace(incoming.getMapPlace());
            campus.setWhatsappUrl(incoming.getWhatsappUrl());
        }
        campus.setUpdatedBy(TenantContext.getUserId());
        Campus saved = campusRepository.save(campus);
        catalogCache.evictTenant(saved.getTenantId());
        return saved;
    }

    @Transactional(readOnly = true)
    public Campus get(UUID id) {
        Campus campus = campusRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Campus", id));
        TenantGuard.assertSameTenant(campus.getTenantId());
        if (CampusScope.restricts() && !campus.getId().equals(CampusScope.current())) {
            // Same answer as a branch that does not exist: other branches are not disclosed.
            throw new ResourceNotFoundException("Campus", id);
        }
        return campus;
    }
}
