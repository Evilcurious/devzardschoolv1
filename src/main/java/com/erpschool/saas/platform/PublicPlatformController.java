package com.erpschool.saas.platform;

import com.erpschool.common.dto.ApiResponse;
import com.erpschool.saas.registration.RegistrationService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Public, unauthenticated platform endpoints: the marketing site content and
 * school registration. Nothing here returns the name, list or details of any
 * school or branch.
 */
@RestController
@RequestMapping("/api/v1/public")
@Tag(name = "Public platform")
public class PublicPlatformController {

    private final PlatformSettingsService settings;
    private final RegistrationService registration;

    public PublicPlatformController(PlatformSettingsService settings, RegistrationService registration) {
        this.settings = settings;
        this.registration = registration;
    }

    @GetMapping("/site")
    public ResponseEntity<ApiResponse<Map<String, Object>>> site() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic())
                .body(ApiResponse.ok(settings.publicSite()));
    }

    @PostMapping(value = "/register", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> register(
            @RequestParam Map<String, String> form,
            @RequestPart(value = "logo", required = false) MultipartFile logo,
            @RequestPart(value = "documents", required = false) List<MultipartFile> documents,
            @RequestParam(value = "documentTypes", required = false) List<String> documentTypes,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(
                "Your school has been registered and is waiting for verification.",
                registration.register(form, logo, documents, documentTypes, request)));
    }

    @GetMapping("/assets/{name}")
    public ResponseEntity<Resource> asset(@PathVariable String name) {
        Path path = settings.asset(name);
        if (path == null || !Files.isRegularFile(path)) {
            return ResponseEntity.notFound().build();
        }
        MediaType type = name.endsWith(".png") ? MediaType.IMAGE_PNG
                : name.endsWith(".webp") ? MediaType.parseMediaType("image/webp") : MediaType.IMAGE_JPEG;
        return ResponseEntity.ok().contentType(type)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                .body(new FileSystemResource(path));
    }
}
