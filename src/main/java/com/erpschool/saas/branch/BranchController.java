package com.erpschool.saas.branch;

import com.erpschool.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Branch management. Authorisation (owner-only writes, branch-scoped reads) is enforced in {@link BranchService}. */
@RestController
@RequestMapping("/api/v1/branches")
@Tag(name = "Branches")
public class BranchController {

    private final BranchService branches;

    public BranchController(BranchService branches) {
        this.branches = branches;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(branches.list()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(@RequestBody Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Branch created", branches.create(body)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> update(@PathVariable UUID id,
                                                                   @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.ok("Branch saved", branches.update(id, body)));
    }

    @PostMapping("/{id}/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status(@PathVariable UUID id,
                                                                   @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.ok("Branch updated",
                branches.setStatus(id, body.get("status") == null ? null : body.get("status").toString())));
    }

    @PutMapping("/{id}/admin")
    public ResponseEntity<ApiResponse<Map<String, Object>>> admin(@PathVariable UUID id,
                                                                  @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.ok("Branch admin assigned", branches.setAdmin(id, body)));
    }

    @PutMapping("/{id}/password")
    public ResponseEntity<ApiResponse<Map<String, Object>>> password(@PathVariable UUID id,
                                                                     @RequestBody Map<String, Object> body) {
        Object password = body.get("password");
        return ResponseEntity.ok(ApiResponse.ok(password == null || password.toString().isBlank()
                        ? "Branch password removed" : "Branch password saved",
                branches.setPassword(id, password == null ? null : password.toString())));
    }

    @PutMapping("/{id}/permissions")
    public ResponseEntity<ApiResponse<Map<String, Object>>> permissions(@PathVariable UUID id,
                                                                        @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.ok("Branch modules saved", branches.setPermissions(id, body)));
    }

    @GetMapping("/{id}/members")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> members(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(branches.members(id)));
    }

    @PostMapping("/{id}/members")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> addMember(@PathVariable UUID id,
                                                                            @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.ok("Access granted",
                branches.addMember(id, UUID.fromString(String.valueOf(body.get("userId"))))));
    }

    @DeleteMapping("/{id}/members/{userId}")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> removeMember(@PathVariable UUID id,
                                                                               @PathVariable UUID userId) {
        return ResponseEntity.ok(ApiResponse.ok("Access removed", branches.removeMember(id, userId)));
    }

    @GetMapping("/staff")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> staff() {
        return ResponseEntity.ok(ApiResponse.ok(branches.staff()));
    }
}
