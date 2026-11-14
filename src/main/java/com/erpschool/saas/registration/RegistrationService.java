package com.erpschool.saas.registration;

import com.erpschool.audit.service.AuditService;
import com.erpschool.common.exception.ApiException;
import com.erpschool.common.exception.BusinessException;
import com.erpschool.common.exception.DuplicateResourceException;
import com.erpschool.common.exception.ForbiddenException;
import com.erpschool.common.exception.ResourceNotFoundException;
import com.erpschool.common.util.HttpRequestUtils;
import com.erpschool.saas.access.TenantAccessGuardFilter;
import com.erpschool.saas.limits.LimitService;
import com.erpschool.saas.platform.PlatformSettingsService;
import com.erpschool.saas.support.Db;
import com.erpschool.saas.support.PrivateFileStore;
import com.erpschool.saas.support.SaasAccess;
import com.erpschool.tenant.context.TenantContext;
import com.erpschool.tenant.entity.Tenant;
import com.erpschool.tenant.entity.TenantStatus;
import com.erpschool.tenant.repository.TenantRepository;
import com.erpschool.user.entity.User;
import com.erpschool.user.entity.UserRole;
import com.erpschool.user.entity.UserStatus;
import com.erpschool.user.repository.UserRepository;
import com.erpschool.user.service.UsernameGenerator;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Free school registration and the owner's side of verification.
 *
 * <p>Registering creates the school (unverified), its owner account and the
 * application with its documents. Nothing of the ERP is usable until the
 * Platform Owner approves the school; that rule is enforced on every request
 * by {@link TenantAccessGuardFilter}, not here.</p>
 */
@Service
public class RegistrationService {

    public static final Set<String> DOCUMENT_TYPES = Set.of("application/pdf", "image/jpeg", "image/png", "image/webp");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]{1,180}\\.[A-Za-z]{2,24}$");
    private static final int MAX_DOCUMENTS = 10;

    private final Db db;
    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final UsernameGenerator usernameGenerator;
    private final PasswordEncoder passwordEncoder;
    private final PrivateFileStore fileStore;
    private final PlatformSettingsService settings;
    private final AuditService auditService;
    private final LimitService limitService;
    private final TenantAccessGuardFilter accessGuard;

    public RegistrationService(Db db, TenantRepository tenantRepository, UserRepository userRepository,
                               UsernameGenerator usernameGenerator, PasswordEncoder passwordEncoder,
                               PrivateFileStore fileStore, PlatformSettingsService settings,
                               AuditService auditService, LimitService limitService,
                               TenantAccessGuardFilter accessGuard) {
        this.db = db;
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.usernameGenerator = usernameGenerator;
        this.passwordEncoder = passwordEncoder;
        this.fileStore = fileStore;
        this.settings = settings;
        this.auditService = auditService;
        this.limitService = limitService;
        this.accessGuard = accessGuard;
    }

    /** Public: register a school. Returns only what the applicant needs to sign in. */
    @Transactional
    public Map<String, Object> register(Map<String, String> form, MultipartFile logo, List<MultipartFile> documents,
                                        List<String> documentTypes, HttpServletRequest request) {
        Map<String, Object> rules = settings.registrationRules();
        if (Boolean.FALSE.equals(rules.get("open"))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "REGISTRATION_CLOSED",
                    "New school registration is closed at the moment. Please contact us.");
        }
        Map<String, String> errors = new LinkedHashMap<>();
        String schoolName = required(form, "schoolName", 2, 150, "School name", errors);
        String address = required(form, "address", 3, 250, "Address", errors);
        String city = required(form, "city", 2, 80, "City", errors);
        String country = required(form, "country", 2, 80, "Country", errors);
        String contactNumber = required(form, "contactNumber", 6, 30, "Contact number", errors);
        String officialEmail = required(form, "officialEmail", 5, 150, "Official email", errors).toLowerCase(Locale.ROOT);
        String principalName = required(form, "principalName", 2, 120, "Principal or owner name", errors);
        String schoolType = required(form, "schoolType", 2, 40, "School type", errors);
        String registrationDetails = optional(form, "registrationDetails", 2000);
        String firstName = required(form, "ownerFirstName", 1, 60, "First name", errors);
        String lastName = required(form, "ownerLastName", 1, 60, "Last name", errors);
        String ownerEmail = required(form, "ownerEmail", 5, 150, "Login email", errors).toLowerCase(Locale.ROOT);
        String password = form.getOrDefault("password", "");

        if (!officialEmail.isEmpty() && !EMAIL.matcher(officialEmail).matches()) {
            errors.put("officialEmail", "Enter a valid email address.");
        }
        if (!ownerEmail.isEmpty() && !EMAIL.matcher(ownerEmail).matches()) {
            errors.put("ownerEmail", "Enter a valid email address.");
        }
        if (!contactNumber.isEmpty() && !contactNumber.matches("^[0-9+()\\- ]{6,30}$")) {
            errors.put("contactNumber", "Use digits, spaces, + or - only.");
        }
        String passwordProblem = passwordProblem(password);
        if (passwordProblem != null) {
            errors.put("password", passwordProblem);
        }
        List<MultipartFile> files = documents == null ? List.of()
                : documents.stream().filter(f -> f != null && !f.isEmpty()).toList();
        int minimum = rules.get("minimumDocuments") instanceof Number n ? Math.max(0, n.intValue()) : 1;
        if (files.size() < minimum) {
            errors.put("documents", minimum == 1 ? "Upload at least one verification document."
                    : "Upload at least " + minimum + " verification documents.");
        }
        if (files.size() > MAX_DOCUMENTS) {
            errors.put("documents", "You can upload up to " + MAX_DOCUMENTS + " documents.");
        }
        if (!errors.isEmpty()) {
            throw new RegistrationInvalidException(errors);
        }
        if (userRepository.existsByEmailIgnoreCase(ownerEmail) || userRepository.existsByUsernameIgnoreCase(ownerEmail)) {
            throw new DuplicateResourceException(
                    "An account with this login email already exists. Sign in, or use a different email.");
        }

        Tenant tenant = new Tenant();
        tenant.setCode(nextCode(schoolName));
        tenant.setName(schoolName);
        tenant.setEmail(officialEmail);
        tenant.setPhone(contactNumber);
        tenant.setAddressLine(address);
        tenant.setCity(city);
        tenant.setCountry(country);
        tenant.setPrincipalName(principalName);
        tenant.setSchoolType(schoolType);
        tenant.setRegistrationNumber(optional(form, "registrationNumber", 120));
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setVerificationStatus("PENDING");
        tenant = tenantRepository.saveAndFlush(tenant);
        UUID tenantId = tenant.getId();

        User owner = new User();
        owner.setTenantId(tenantId);
        owner.setUsername(usernameGenerator.next(tenantId, tenant.getCode(), UserRole.SCHOOL_ADMIN));
        owner.setEmail(ownerEmail);
        owner.setPasswordHash(passwordEncoder.encode(password));
        owner.setFirstName(firstName);
        owner.setLastName(lastName);
        owner.setPhone(contactNumber);
        owner.setRole(UserRole.SCHOOL_ADMIN);
        owner.setStatus(UserStatus.ACTIVE);
        owner.setMustChangePassword(false);
        owner = userRepository.saveAndFlush(owner);
        UUID ownerId = owner.getId();

        // The users trigger created a School Admin membership; the registrant is the owner.
        db.update("""
                UPDATE user_school_memberships SET role = 'SCHOOL_OWNER'
                WHERE user_id = :u AND tenant_id = :t
                """, Db.p("u", ownerId, "t", tenantId));
        db.update("UPDATE tenants SET owner_user_id = :u WHERE id = :t", Db.p("u", ownerId, "t", tenantId));

        String logoUrl = null;
        if (logo != null && !logo.isEmpty()) {
            PrivateFileStore.Stored stored = fileStore.store("_verification", tenantId.toString(), logo,
                    Set.of("image/jpeg", "image/png", "image/webp"));
            logoUrl = stored.key();
        }
        Map<String, Object> app = db.one("""
                INSERT INTO school_applications (tenant_id, applicant_user_id, school_name, logo_url, address, city,
                    country, contact_number, official_email, principal_name, school_type, registration_details,
                    status, submitted_at)
                VALUES (:t, :u, :name, :logo, :address, :city, :country, :contact, :email, :principal, :type,
                    :details, 'PENDING', NOW())
                RETURNING id AS "id"
                """, Db.p("t", tenantId, "u", ownerId, "name", schoolName, "logo", logoUrl, "address", address,
                "city", city, "country", country, "contact", contactNumber, "email", officialEmail,
                "principal", principalName, "type", schoolType, "details", registrationDetails));
        UUID applicationId = (UUID) app.get("id");
        List<String> storedKeys = new ArrayList<>();
        try {
            for (int i = 0; i < files.size(); i++) {
                String type = documentTypes != null && i < documentTypes.size() ? documentTypes.get(i) : null;
                storedKeys.add(saveDocument(tenantId, applicationId, ownerId, files.get(i), type));
            }
        } catch (RuntimeException e) {
            // The database rolls back with the transaction; remove the files written so far as well.
            storedKeys.forEach(fileStore::deleteQuietly);
            if (logoUrl != null) {
                fileStore.deleteQuietly(logoUrl);
            }
            throw e;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("school", schoolName);
        details.put("documents", files.size());
        auditService.recordSameTransaction(tenantId, ownerId, owner.getUsername(), "SCHOOL_OWNER",
                "SCHOOL_REGISTERED", "Tenant", tenantId.toString(), details,
                HttpRequestUtils.clientIp(request), HttpRequestUtils.userAgent(request));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schoolName", schoolName);
        out.put("loginEmail", ownerEmail);
        out.put("username", owner.getUsername());
        out.put("verificationStatus", "PENDING");
        return out;
    }

    /** The signed-in owner's application, documents and current decision. */
    @Transactional(readOnly = true)
    public Map<String, Object> mine() {
        UUID tenantId = SaasAccess.requireSchoolWide();
        return view(tenantId, false);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> view(UUID tenantId, boolean forPlatformOwner) {
        Map<String, Object> tenant = db.one("""
                SELECT t.id AS "tenantId", t.name AS "schoolName", t.code AS "code",
                       t.verification_status AS "verificationStatus", t.verified_at AS "verifiedAt",
                       t.status AS "status", t.created_at AS "registeredAt"
                FROM tenants t WHERE t.id = :t
                """, Db.p("t", tenantId));
        if (tenant == null) {
            throw new ResourceNotFoundException("School", tenantId);
        }
        Map<String, Object> application = db.one("""
                SELECT a.id AS "id", a.school_name AS "schoolName", (a.logo_url IS NOT NULL) AS "hasLogo",
                       a.address AS "address", a.city AS "city", a.country AS "country",
                       a.contact_number AS "contactNumber", a.official_email AS "officialEmail",
                       a.principal_name AS "principalName", a.school_type AS "schoolType",
                       a.registration_details AS "registrationDetails", a.status AS "status",
                       a.documents_requested AS "documentsRequested",
                       a.documents_request_note AS "documentsRequestNote",
                       a.rejection_reason AS "rejectionReason", a.suspension_reason AS "suspensionReason",
                       a.review_note AS "reviewNote", a.submitted_at AS "submittedAt", a.reviewed_at AS "reviewedAt"
                FROM school_applications a WHERE a.tenant_id = :t
                """, Db.p("t", tenantId));
        if (application != null && !forPlatformOwner) {
            application.remove("reviewNote"); // internal note for the platform team only
        }
        Map<String, Object> out = new LinkedHashMap<>(tenant);
        out.put("application", application);
        out.put("documents", application == null ? List.of() : db.list("""
                SELECT d.id AS "id", d.document_type AS "documentType", d.original_name AS "fileName",
                       d.content_type AS "contentType", d.size_bytes AS "sizeBytes", d.status AS "status",
                       d.review_note AS "reviewNote", d.uploaded_at AS "uploadedAt"
                FROM school_verifications d WHERE d.tenant_id = :t ORDER BY d.uploaded_at
                """, Db.p("t", tenantId)));
        out.put("canEdit", !"VERIFIED".equals(tenant.get("verificationStatus"))
                && !"SUSPENDED".equals(tenant.get("verificationStatus")));
        return out;
    }

    /** Owner corrects the application (allowed until the school is verified) and sends it back for review. */
    @Transactional
    public Map<String, Object> update(Map<String, Object> body) {
        UUID tenantId = SaasAccess.requireSchoolOwner();
        Map<String, Object> current = editable(tenantId);
        Map<String, String> form = new LinkedHashMap<>();
        body.forEach((k, v) -> form.put(k, v == null ? "" : v.toString()));
        Map<String, String> errors = new LinkedHashMap<>();
        String schoolName = required(form, "schoolName", 2, 150, "School name", errors);
        String address = required(form, "address", 3, 250, "Address", errors);
        String city = required(form, "city", 2, 80, "City", errors);
        String country = required(form, "country", 2, 80, "Country", errors);
        String contactNumber = required(form, "contactNumber", 6, 30, "Contact number", errors);
        String officialEmail = required(form, "officialEmail", 5, 150, "Official email", errors).toLowerCase(Locale.ROOT);
        String principalName = required(form, "principalName", 2, 120, "Principal or owner name", errors);
        String schoolType = required(form, "schoolType", 2, 40, "School type", errors);
        if (!officialEmail.isEmpty() && !EMAIL.matcher(officialEmail).matches()) {
            errors.put("officialEmail", "Enter a valid email address.");
        }
        if (!errors.isEmpty()) {
            throw new RegistrationInvalidException(errors);
        }
        db.update("""
                UPDATE school_applications SET school_name = :name, address = :address, city = :city,
                    country = :country, contact_number = :contact, official_email = :email,
                    principal_name = :principal, school_type = :type, registration_details = :details
                WHERE id = :id
                """, Db.p("id", current.get("id"), "name", schoolName, "address", address, "city", city,
                "country", country, "contact", contactNumber, "email", officialEmail, "principal", principalName,
                "type", schoolType, "details", optional(form, "registrationDetails", 2000)));
        db.update("""
                UPDATE tenants SET name = :name, address_line = :address, city = :city, country = :country,
                    phone = :contact, email = :email, principal_name = :principal, school_type = :type
                WHERE id = :t
                """, Db.p("t", tenantId, "name", schoolName, "address", address, "city", city, "country", country,
                "contact", contactNumber, "email", officialEmail, "principal", principalName, "type", schoolType));
        auditService.record("SCHOOL_APPLICATION_UPDATED", "Tenant", tenantId.toString(), Map.of("school", schoolName));
        return view(tenantId, false);
    }

    /** Owner adds a verification document (also the answer to "more documents requested"). */
    @Transactional
    public Map<String, Object> addDocument(MultipartFile file, String documentType) {
        UUID tenantId = SaasAccess.requireSchoolOwner();
        Map<String, Object> current = editable(tenantId);
        long count = db.count("SELECT COUNT(*) FROM school_verifications WHERE tenant_id = :t", Db.p("t", tenantId));
        if (count >= MAX_DOCUMENTS * 2L) {
            throw new BusinessException("DOCUMENT_LIMIT", "This application already has the maximum number of documents.");
        }
        limitService.assertStorage(tenantId, file == null ? 0 : file.getSize());
        saveDocument(tenantId, (UUID) current.get("id"), SaasAccess.userId(), file, documentType);
        auditService.record("SCHOOL_DOCUMENT_UPLOADED", "Tenant", tenantId.toString(),
                Map.of("type", documentType == null ? "Other" : documentType));
        return view(tenantId, false);
    }

    /** Owner removes a document that has not been accepted yet. */
    @Transactional
    public Map<String, Object> removeDocument(UUID documentId) {
        UUID tenantId = SaasAccess.requireSchoolOwner();
        editable(tenantId);
        Map<String, Object> doc = db.one("""
                SELECT storage_key AS "key", status AS "status" FROM school_verifications
                WHERE id = :id AND tenant_id = :t
                """, Db.p("id", documentId, "t", tenantId));
        if (doc == null) {
            throw new ResourceNotFoundException("Document", documentId);
        }
        if ("ACCEPTED".equals(doc.get("status"))) {
            throw new BusinessException("DOCUMENT_ACCEPTED", "An accepted document cannot be removed.");
        }
        db.update("DELETE FROM school_verifications WHERE id = :id AND tenant_id = :t",
                Db.p("id", documentId, "t", tenantId));
        fileStore.deleteQuietly((String) doc.get("key"));
        return view(tenantId, false);
    }

    /** Owner sends a rejected or "documents requested" application back for review. */
    @Transactional
    public Map<String, Object> resubmit() {
        UUID tenantId = SaasAccess.requireSchoolOwner();
        Map<String, Object> current = editable(tenantId);
        Map<String, Object> rules = settings.registrationRules();
        int minimum = rules.get("minimumDocuments") instanceof Number n ? Math.max(0, n.intValue()) : 1;
        long usable = db.count("""
                SELECT COUNT(*) FROM school_verifications WHERE tenant_id = :t AND status <> 'REJECTED'
                """, Db.p("t", tenantId));
        if (usable < minimum) {
            throw new BusinessException("DOCUMENTS_REQUIRED",
                    "Upload at least " + minimum + " document" + (minimum == 1 ? "" : "s") + " before sending for review.");
        }
        db.update("""
                UPDATE school_applications SET status = 'PENDING', documents_requested = FALSE,
                    submitted_at = NOW(), rejection_reason = NULL
                WHERE id = :id
                """, Db.p("id", current.get("id")));
        db.update("UPDATE tenants SET verification_status = 'PENDING' WHERE id = :t", Db.p("t", tenantId));
        accessGuard.evictTenant(tenantId);
        auditService.record("SCHOOL_APPLICATION_RESUBMITTED", "Tenant", tenantId.toString(), Map.of());
        return view(tenantId, false);
    }

    /**
     * A verification document for download. School users get their own school's
     * documents only (the tenant comes from the session, never from the request);
     * the Platform Owner names the school.
     */
    @Transactional(readOnly = true)
    public Document document(UUID tenantIdForPlatformOwner, UUID documentId) {
        UUID tenantId;
        if (TenantContext.getRole() == UserRole.ERP_OWNER) {
            tenantId = tenantIdForPlatformOwner;
        } else {
            tenantId = SaasAccess.requireSchoolWide();
        }
        Map<String, Object> doc = db.one("""
                SELECT storage_key AS "key", original_name AS "name", content_type AS "type"
                FROM school_verifications WHERE id = :id AND tenant_id = :t
                """, Db.p("id", documentId, "t", tenantId));
        if (doc == null) {
            throw new ResourceNotFoundException("Document", documentId);
        }
        return new Document(fileStore.resolve((String) doc.get("key")), (String) doc.get("name"),
                (String) doc.get("type"));
    }

    public record Document(Path path, String name, String contentType) {
    }

    private String saveDocument(UUID tenantId, UUID applicationId, UUID userId, MultipartFile file, String type) {
        PrivateFileStore.Stored stored = fileStore.store("_verification", tenantId.toString(), file, DOCUMENT_TYPES);
        String documentType = type == null || type.isBlank() ? "Other" : type.trim();
        if (documentType.length() > 40) {
            documentType = documentType.substring(0, 40);
        }
        db.update("""
                INSERT INTO school_verifications (tenant_id, application_id, document_type, original_name, storage_key,
                    content_type, size_bytes, status, uploaded_by)
                VALUES (:t, :app, :type, :name, :key, :contentType, :size, 'SUBMITTED', :by)
                """, Db.p("t", tenantId, "app", applicationId, "type", documentType, "name", stored.originalName(),
                "key", stored.key(), "contentType", stored.contentType(), "size", stored.size(), "by", userId));
        return stored.key();
    }

    private Map<String, Object> editable(UUID tenantId) {
        Map<String, Object> row = db.one("""
                SELECT a.id AS "id", t.verification_status AS "verification"
                FROM tenants t LEFT JOIN school_applications a ON a.tenant_id = t.id WHERE t.id = :t
                """, Db.p("t", tenantId));
        if (row == null || row.get("id") == null) {
            throw new BusinessException("NO_APPLICATION", "This school has no open application.");
        }
        String status = (String) row.get("verification");
        if ("VERIFIED".equals(status) || "SUSPENDED".equals(status)) {
            throw new ForbiddenException("Your school is already " + status.toLowerCase(Locale.ROOT)
                    + "; the application can no longer be changed.");
        }
        return row;
    }

    /** Short unique school code from the name's initials, for example "Sunrise Public School" gives SPS. */
    private String nextCode(String schoolName) {
        StringBuilder initials = new StringBuilder();
        for (String word : schoolName.toUpperCase(Locale.ROOT).split("[^A-Z0-9]+")) {
            if (!word.isEmpty() && initials.length() < 5) {
                initials.append(word.charAt(0));
            }
        }
        String base = initials.length() >= 2 ? initials.toString()
                : (schoolName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "") + "SCH").substring(0, 3);
        for (int i = 0; i < 500; i++) {
            String candidate = i == 0 ? base : base + (i + 1);
            if (db.count("SELECT COUNT(*) FROM tenants WHERE code = :c OR LOWER(slug) = LOWER(:c)",
                    Db.p("c", candidate)) == 0) {
                return candidate;
            }
        }
        return base + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
    }

    public static String passwordProblem(String password) {
        if (password == null || password.length() < 8) {
            return "Use at least 8 characters.";
        }
        if (password.length() > 100) {
            return "Use at most 100 characters.";
        }
        if (!password.matches(".*[A-Za-z].*") || !password.matches(".*[0-9].*")) {
            return "Include at least one letter and one number.";
        }
        return null;
    }

    private static String required(Map<String, String> form, String key, int min, int max, String label,
                                   Map<String, String> errors) {
        String value = form.get(key) == null ? "" : form.get(key).trim().replaceAll("\\s+", " ");
        if (value.isEmpty()) {
            errors.put(key, label + " is required.");
        } else if (value.length() < min || value.length() > max) {
            errors.put(key, label + " must be " + min + " to " + max + " characters.");
        }
        return value;
    }

    private static String optional(Map<String, String> form, String key, int max) {
        String value = form.get(key) == null ? "" : form.get(key).trim();
        if (value.isEmpty()) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }

    /** Field-level validation failure; rendered as a 400 with per-field messages. */
    public static class RegistrationInvalidException extends RuntimeException {
        private final transient Map<String, String> errors;

        public RegistrationInvalidException(Map<String, String> errors) {
            super("Please correct the highlighted fields.");
            this.errors = errors;
        }

        public Map<String, String> getErrors() {
            return errors;
        }
    }
}
