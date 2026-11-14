package com.erpschool.saas.platform;

import com.erpschool.common.dto.ApiResponse;
import com.erpschool.saas.plan.PlanService;
import com.erpschool.saas.registration.RegistrationService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Platform Owner API. Every method is restricted to the platform owner, here and again in the services. */
@RestController
@RequestMapping("/api/v1/platform")
@PreAuthorize("hasRole('ERP_OWNER')")
@Tag(name = "Platform owner")
public class PlatformController {

    private final PlatformSchoolService schools;
    private final PlanService plans;
    private final PlatformSettingsService settings;
    private final RegistrationService registration;

    public PlatformController(PlatformSchoolService schools, PlanService plans, PlatformSettingsService settings,
                              RegistrationService registration) {
        this.schools = schools;
        this.plans = plans;
        this.settings = settings;
        this.registration = registration;
    }

    @GetMapping("/overview")
    public ResponseEntity<ApiResponse<Map<String, Object>>> overview() {
        return ResponseEntity.ok(ApiResponse.ok(schools.overview()));
    }

    @GetMapping("/schools")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> schools(
            @RequestParam(required = false) String status, @RequestParam(required = false) String q) {
        return ResponseEntity.ok(ApiResponse.ok(schools.schools(status, q)));
    }

    @GetMapping("/schools/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> school(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(schools.school(id)));
    }

    @PostMapping("/schools/{id}/review")
    public ResponseEntity<ApiResponse<Map<String, Object>>> review(@PathVariable UUID id,
                                                                   @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.ok("Saved", schools.review(id, str(body.get("action")), str(body.get("note")))));
    }

    @PutMapping("/schools/{id}/limits")
    public ResponseEntity<ApiResponse<Map<String, Object>>> limits(@PathVariable UUID id,
                                                                   @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.ok("Limits saved", plans.setLimits(id, body)));
    }

    @PutMapping("/schools/{id}/plan")
    public ResponseEntity<ApiResponse<Map<String, Object>>> plan(@PathVariable UUID id,
                                                                 @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.ok("Plan changed", plans.assign(id, body)));
    }

    @PostMapping("/schools/{id}/documents/{documentId}/review")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reviewDocument(@PathVariable UUID id,
                                                                           @PathVariable UUID documentId,
                                                                           @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.ok("Saved",
                schools.reviewDocument(id, documentId, str(body.get("status")), str(body.get("note")))));
    }

    @GetMapping("/schools/{id}/documents/{documentId}")
    public ResponseEntity<Resource> document(@PathVariable UUID id, @PathVariable UUID documentId) {
        return stream(registration.document(id, documentId));
    }

    @GetMapping("/plans")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> plans() {
        return ResponseEntity.ok(ApiResponse.ok(plans.all()));
    }

    @PostMapping("/plans")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createPlan(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.ok("Plan created", plans.create(body)));
    }

    @PutMapping("/plans/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updatePlan(@PathVariable UUID id,
                                                                       @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.ok("Plan saved", plans.update(id, body)));
    }

    @GetMapping("/settings")
    public ResponseEntity<ApiResponse<Map<String, Object>>> settings() {
        return ResponseEntity.ok(ApiResponse.ok(settings.all()));
    }

    @PutMapping("/settings/{key}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> saveSetting(@PathVariable String key,
                                                                        @RequestBody Object value) {
        return ResponseEntity.ok(ApiResponse.ok("Saved", settings.put(key, value)));
    }

    @PostMapping(value = "/assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> uploadAsset(@RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok(ApiResponse.ok("Uploaded", settings.uploadAsset(file)));
    }

    public static ResponseEntity<Resource> stream(RegistrationService.Document doc) {
        if (!Files.isRegularFile(doc.path())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(doc.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(doc.name(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(new FileSystemResource(doc.path()));
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }
}
