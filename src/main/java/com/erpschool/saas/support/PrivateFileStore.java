package com.erpschool.saas.support;

import com.erpschool.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Storage for files that are never reachable by URL: school verification
 * documents and platform assets. They live outside the per-school upload
 * folders, under a key the application resolves itself, and are streamed only
 * through controllers that check who is asking.
 */
@Component
public class PrivateFileStore {

    public static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final Map<String, String> EXTENSIONS = Map.of(
            "application/pdf", "pdf", "image/jpeg", "jpg", "image/png", "png", "image/webp", "webp");

    private final Path root;

    public PrivateFileStore(@Value("${app.upload-dir:uploads}") String uploadDir) {
        this.root = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    public record Stored(String key, String originalName, String contentType, long size) {
    }

    /**
     * Validate (size, declared type, and the real file signature) and store.
     *
     * @param area    top-level private folder, for example "_verification"
     * @param owner   sub-folder, usually the school id
     * @param allowed accepted content types
     */
    public Stored store(String area, String owner, MultipartFile file, Set<String> allowed) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("FILE_REQUIRED", "Choose a file to upload.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BusinessException("FILE_TOO_LARGE", "Each file can be up to 10 MB.");
        }
        byte[] head;
        try (InputStream in = file.getInputStream()) {
            head = in.readNBytes(16);
        } catch (IOException e) {
            throw new BusinessException("FILE_TYPE", "Could not read the uploaded file.");
        }
        // The type is decided by the file's own signature, never by the name or the browser's claim.
        String contentType = sniff(head);
        if (contentType == null || !allowed.contains(contentType)) {
            throw new BusinessException("FILE_TYPE", "Only PDF, JPG, PNG or WEBP files are accepted.");
        }
        String original = file.getOriginalFilename() == null ? "document"
                : Path.of(file.getOriginalFilename().replace('\\', '/')).getFileName().toString()
                .replaceAll("[^a-zA-Z0-9._ -]", "_");
        if (original.length() > 120) {
            original = original.substring(original.length() - 120);
        }
        String key = area + "/" + owner + "/" + UUID.randomUUID() + "." + EXTENSIONS.get(contentType);
        Path dest = resolve(key);
        try {
            Files.createDirectories(dest.getParent());
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new BusinessException("FILE_STORE_FAILED", "Could not store the file. Please try again.");
        }
        return new Stored(key, original, contentType, file.getSize());
    }

    public Path resolve(String key) {
        Path dest = root.resolve(key).normalize();
        if (!dest.startsWith(root) || key.contains("..")) {
            throw new BusinessException("FILE_PATH", "Invalid file path");
        }
        return dest;
    }

    public void deleteQuietly(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException | RuntimeException ignored) {
            /* best effort */
        }
    }

    static String sniff(byte[] h) {
        if (h.length >= 5 && h[0] == '%' && h[1] == 'P' && h[2] == 'D' && h[3] == 'F' && h[4] == '-') {
            return "application/pdf";
        }
        if (h.length >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (h.length >= 8 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') {
            return "image/png";
        }
        if (h.length >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    public static String extension(String contentType) {
        return EXTENSIONS.getOrDefault(contentType == null ? "" : contentType.toLowerCase(Locale.ROOT), "bin");
    }
}
