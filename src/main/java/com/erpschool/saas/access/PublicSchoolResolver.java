package com.erpschool.saas.access;

import com.erpschool.campus.entity.Campus;
import com.erpschool.common.exception.ResourceNotFoundException;
import com.erpschool.tenant.entity.Tenant;
import com.erpschool.tenant.entity.TenantStatus;
import com.erpschool.tenant.repository.TenantRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Which school an unauthenticated request may see.
 *
 * <p>The platform never lists or suggests schools. A school's own public page
 * and admission form are reachable only by that school's code, and only once
 * the school is verified and active. Every other case gets the same "not
 * found", so a stranger cannot tell whether a code exists, is pending, or was
 * suspended.</p>
 */
@Component
public class PublicSchoolResolver {

    private final TenantRepository tenantRepository;
    private final String defaultCode;

    public PublicSchoolResolver(TenantRepository tenantRepository,
                                @Value("${app.public.default-school-code:}") String defaultCode) {
        this.tenantRepository = tenantRepository;
        this.defaultCode = defaultCode == null ? "" : defaultCode.trim();
    }

    public Tenant resolve(String code) {
        String wanted = code == null || code.isBlank() ? defaultCode : code.trim();
        if (wanted.isEmpty() || wanted.length() > 20) {
            throw notFound();
        }
        Tenant tenant = tenantRepository.findByCodeIgnoreCase(wanted).orElseThrow(PublicSchoolResolver::notFound);
        if (!tenant.isVerified() || tenant.getStatus() != TenantStatus.ACTIVE) {
            throw notFound();
        }
        return tenant;
    }

    /** Branches a school shows publicly: all active ones if it opted in, otherwise only its main branch. */
    public List<Campus> publicBranches(Tenant tenant, List<Campus> all) {
        List<Campus> active = all.stream().filter(Campus::isUsable).toList();
        if (tenant.isPublicBranchesVisible()) {
            return active;
        }
        return active.stream().filter(Campus::isMain).limit(1).toList();
    }

    private static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("School not found");
    }
}
