package com.erpschool.saas.platform;

import com.erpschool.audit.service.AuditService;
import com.erpschool.common.exception.BusinessException;
import com.erpschool.saas.plan.PlanService;
import com.erpschool.saas.support.Db;
import com.erpschool.saas.support.PrivateFileStore;
import com.erpschool.saas.support.SaasAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything the Platform Owner can change about the platform itself:
 * branding, homepage, navigation, footer, contact details, the announcement
 * banner, registration rules and monitoring thresholds. Stored as JSON
 * documents so new options need no schema change.
 */
@Service
public class PlatformSettingsService {

    /** Known documents and whether visitors may read them. */
    private static final Map<String, Boolean> KEYS = Map.of(
            "branding", true, "homepage", true, "navigation", true, "footer", true,
            "contact", true, "announcement", true, "registration", true, "monitoring", false);
    private static final long CACHE_MS = 30_000;
    private static final int MAX_JSON = 200_000;

    private final Db db;
    private final PlanService planService;
    private final PrivateFileStore fileStore;
    private final AuditService auditService;
    private volatile Map<String, Object> publicCache;
    private volatile long publicCacheUntil;

    public PlatformSettingsService(Db db, PlanService planService, PrivateFileStore fileStore,
                                   AuditService auditService) {
        this.db = db;
        this.planService = planService;
        this.fileStore = fileStore;
        this.auditService = auditService;
    }

    /** Public site document: only settings marked public, plus public plans. No school data. */
    @Transactional(readOnly = true)
    public Map<String, Object> publicSite() {
        long now = System.currentTimeMillis();
        Map<String, Object> cached = publicCache;
        if (cached != null && publicCacheUntil > now) {
            return cached;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map<String, Object> row : db.list("""
                SELECT key AS "key", value AS "value" FROM platform_settings WHERE is_public ORDER BY key
                """, Db.p())) {
            out.put((String) row.get("key"), row.get("value"));
        }
        out.put("plans", planService.publicPlans());
        publicCache = out;
        publicCacheUntil = now + CACHE_MS;
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> all() {
        SaasAccess.requirePlatformOwner();
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map<String, Object> row : db.list("""
                SELECT key AS "key", value AS "value" FROM platform_settings ORDER BY key
                """, Db.p())) {
            out.put((String) row.get("key"), row.get("value"));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Object get(String key) {
        Map<String, Object> row = db.one("SELECT value AS \"value\" FROM platform_settings WHERE key = :key",
                Db.p("key", key));
        return row == null ? null : row.get("value");
    }

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public Map<String, Object> registrationRules() {
        Object value = get("registration");
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @Transactional
    public Map<String, Object> put(String key, Object value) {
        SaasAccess.requirePlatformOwner();
        Boolean isPublic = KEYS.get(key);
        if (isPublic == null) {
            throw new BusinessException("SETTING_UNKNOWN", "Unknown platform setting: " + key);
        }
        if (value == null || !(value instanceof Map<?, ?> || value instanceof List<?>)) {
            throw new BusinessException("SETTING_VALUE", "A settings document is required.");
        }
        String json = db.json(value);
        if (json.length() > MAX_JSON) {
            throw new BusinessException("SETTING_VALUE", "That settings document is too large.");
        }
        db.update("""
                INSERT INTO platform_settings (key, value, is_public, updated_by, updated_at)
                VALUES (:key, CAST(:value AS jsonb), :isPublic, :by, NOW())
                ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_by = EXCLUDED.updated_by,
                    updated_at = NOW()
                """, Db.p("key", key, "value", json, "isPublic", isPublic, "by", SaasAccess.userId()));
        publicCache = null;
        auditService.record("PLATFORM_SETTING_CHANGED", "PlatformSetting", key, Map.of("key", key));
        return all();
    }

    /** Upload a platform image (logo, hero art). Served publicly at /api/v1/public/assets/{name}. */
    public Map<String, Object> uploadAsset(MultipartFile file) {
        SaasAccess.requirePlatformOwner();
        PrivateFileStore.Stored stored = fileStore.store("_platform", "assets", file,
                Set.of("image/jpeg", "image/png", "image/webp"));
        String name = Path.of(stored.key()).getFileName().toString();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("url", "/api/v1/public/assets/" + name);
        out.put("fileName", stored.originalName());
        out.put("contentType", stored.contentType());
        out.put("sizeBytes", stored.size());
        return out;
    }

    public Path asset(String name) {
        if (name == null || !name.matches("^[0-9a-fA-F-]{36}\\.(jpg|png|webp)$")) {
            return null;
        }
        return fileStore.resolve("_platform/assets/" + name);
    }
}
