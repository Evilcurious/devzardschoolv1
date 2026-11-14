package com.erpschool.saas.registration;

import com.erpschool.common.dto.ApiResponse;
import com.erpschool.saas.SaasRole;
import com.erpschool.saas.limits.LimitService;
import com.erpschool.saas.plan.PlanService;
import com.erpschool.saas.platform.PlatformController;
import com.erpschool.saas.platform.PlatformSettingsService;
import com.erpschool.saas.support.Db;
import com.erpschool.saas.support.SaasAccess;
import com.erpschool.tenant.context.TenantContext;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A school's own account: verification status, plan, limits and usage.
 * The school is always the one in the session; no endpoint accepts a school id.
 */
@RestController
@RequestMapping("/api/v1/school")
@Tag(name = "School account")
public class SchoolAccountController {

    private final RegistrationService registration;
    private final LimitService limits;
    private final PlanService plans;
    private final PlatformSettingsService settings;
    private final Db db;

    public SchoolAccountController(RegistrationService registration, LimitService limits, PlanService plans,
                                   PlatformSettingsService settings, Db db) {
        this.registration = registration;
        this.limits = limits;
        this.plans = plans;
        this.settings = settings;
        this.db = db;
    }

    /** Dashboard header for owners and admins: status, plan, limits, usage. */
    @GetMapping("/overview")
    public ResponseEntity<ApiResponse<Map<String, Object>>> overview() {
        UUID tenantId = SaasAccess.requireManager();
        SaasRole role = TenantContext.getSaasRole();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("school", db.one("""
                SELECT t.id AS "id", t.name AS "name", t.code AS "code", t.status AS "status",
                       t.verification_status AS "verificationStatus", t.verified_at AS "verifiedAt",
                       a.rejection_reason AS "rejectionReason", a.suspension_reason AS "suspensionReason",
                       a.documents_requested AS "documentsRequested",
                       a.documents_request_note AS "documentsRequestNote"
                FROM tenants t LEFT JOIN school_applications a ON a.tenant_id = t.id WHERE t.id = :t
                """, Db.p("t", tenantId)));
        out.put("role", role == null ? null : role.name());
        if (role != null && role.schoolWide()) {
            // Plan and school-wide usage are for the owner and school admins, not for a single branch.
            out.put("resources", limits.summary(tenantId));
            out.put("plans", plans.publicPlans());
        }
        out.put("contact", settings.get("contact"));
        return ResponseEntity.ok(ApiResponse.ok(out));
    }

    @GetMapping("/application")
    public ResponseEntity<ApiResponse<Map<String, Object>>> application() {
        Map<String, Object> out = registration.mine();
        out.put("rules", settings.registrationRules());
        return ResponseEntity.ok(ApiResponse.ok(out));
    }

    @PutMapping("/application")
    public ResponseEntity<ApiResponse<Map<String, Object>>> update(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.ok("Saved", registration.update(body)));
    }

    @PostMapping("/application/resubmit")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resubmit() {
        return ResponseEntity.ok(ApiResponse.ok("Sent for review", registration.resubmit()));
    }

    @PostMapping(value = "/application/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> addDocument(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "documentType", required = false) String documentType) {
        return ResponseEntity.ok(ApiResponse.ok("Document uploaded", registration.addDocument(file, documentType)));
    }

    @DeleteMapping("/application/documents/{documentId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> removeDocument(@PathVariable UUID documentId) {
        return ResponseEntity.ok(ApiResponse.ok("Document removed", registration.removeDocument(documentId)));
    }

    @GetMapping("/application/documents/{documentId}")
    public ResponseEntity<Resource> document(@PathVariable UUID documentId) {
        return PlatformController.stream(registration.document(null, documentId));
    }
}
