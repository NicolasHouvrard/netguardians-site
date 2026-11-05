package com.logicielpourtous.site.features.api.angular;

import com.logicielpourtous.site.features.audit.AuditLog;
import com.logicielpourtous.site.features.audit.AuditLogService;
import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.role.Role;
import com.logicielpourtous.site.features.role.RoleRepository;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/intra/admin")
public class AdminApiController {

    private final UserRepository     userRepository;
    private final CustomerRepository customerRepository;
    private final RoleRepository     roleRepository;
    private final AuditLogService    auditLogService;

    public AdminApiController(UserRepository userRepository,
                              CustomerRepository customerRepository,
                              RoleRepository roleRepository,
                              AuditLogService auditLogService) {
        this.userRepository     = userRepository;
        this.customerRepository = customerRepository;
        this.roleRepository     = roleRepository;
        this.auditLogService    = auditLogService;
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    public record UserDto(
            Long         id,
            String       email,
            String       fullName,
            boolean      active,
            boolean      mustChangePassword,
            String       type,           // "INTERNE" | "PORTAIL"
            List<String> roles,
            String       tenantName,
            String       clientName,     // nom du customer pour les utilisateurs portail
            String       createdAt,
            String       lastLoginIp
    ) {
        static UserDto from(User u, Map<Long, String> customerNames) {
            boolean interne = u.getCustomerId() == null ||
                    u.getRoles().stream().anyMatch(r -> "ESN".equals(r.getType()));
            String clientName = (!interne && u.getCustomerId() != null)
                    ? customerNames.getOrDefault(u.getCustomerId(), "—")
                    : null;
            return new UserDto(
                    u.getId(),
                    u.getEmail(),
                    u.getFullName() != null ? u.getFullName() : u.getEmail(),
                    u.isActive(),
                    u.isMustChangePassword(),
                    interne ? "INTERNE" : "PORTAIL",
                    u.getRoles().stream().map(Role::getName).sorted().toList(),
                    u.getTenant() != null ? u.getTenant().getName() : null,
                    clientName,
                    u.getCreatedAt() != null ? u.getCreatedAt().toString() : null,
                    u.getLastLoginIp()
            );
        }
    }

    public record RoleDto(Long id, String name, String label, String type) {
        static RoleDto from(Role r) {
            return new RoleDto(r.getId(), r.getName(), r.getLabel(), r.getType());
        }
    }

    public record SetRolesRequest(List<String> roleNames) {}

    public record StatsDto(long total, long active, long interne, long portail, long roles) {}

    public record AuditLogDto(
            Long   id, String type,
            Long   userId, String email,
            String ip, String country, String countryCode, String city,
            String url, String userAgent, String timestamp) {
        static AuditLogDto from(AuditLog a) {
            return new AuditLogDto(
                    a.getId(), a.getType().name(),
                    a.getUserId(), a.getEmail(),
                    a.getIp(), a.getCountry(), a.getCountryCode(), a.getCity(),
                    a.getUrl(), a.getUserAgent(),
                    a.getTimestamp() != null ? a.getTimestamp().toString() : null);
        }
    }

    public record SaveRoleRequest(String name, String label, String type) {}

    // ── GET /api/intra/admin/users ────────────────────────────────────────────

    @GetMapping("/users")
    public List<UserDto> users(
            @RequestParam(required = false) String  q,
            @RequestParam(required = false) String  type,
            @RequestParam(required = false) Boolean active) {

        Map<Long, String> customerNames = customerRepository.findAll().stream()
                .collect(Collectors.toMap(
                        c -> c.getId(),
                        c -> c.getCompanyName() != null ? c.getCompanyName() : ""));

        return userRepository.findAll().stream()
                .filter(u -> {
                    if (q != null && !q.isBlank()) {
                        String lq = q.toLowerCase();
                        boolean match = (u.getEmail()    != null && u.getEmail().toLowerCase().contains(lq))
                                     || (u.getFullName() != null && u.getFullName().toLowerCase().contains(lq));
                        if (!match) return false;
                    }
                    if (type != null) {
                        boolean interne = u.getCustomerId() == null ||
                                u.getRoles().stream().anyMatch(r -> "ESN".equals(r.getType()));
                        if ("INTERNE".equalsIgnoreCase(type) && !interne)  return false;
                        if ("PORTAIL".equalsIgnoreCase(type) &&  interne)  return false;
                    }
                    if (active != null && u.isActive() != active) return false;
                    return true;
                })
                .sorted(java.util.Comparator.comparing(
                        u -> u.getFullName() != null ? u.getFullName().toLowerCase() : ""))
                .map(u -> UserDto.from(u, customerNames))
                .toList();
    }

    // ── GET /api/intra/admin/audit-logs ──────────────────────────────────────

    @GetMapping("/audit-logs")
    public List<AuditLogDto> auditLogs(
            @RequestParam(required = false) String  type,
            @RequestParam(required = false) String  email,
            @RequestParam(required = false) String  ip,
            @RequestParam(defaultValue  = "200")    int limit) {
        return auditLogService.search(type, email, ip, limit)
                .stream().map(AuditLogDto::from).toList();
    }

    // ── GET /api/intra/admin/stats ────────────────────────────────────────────

    @GetMapping("/stats")
    public StatsDto stats() {
        List<User> all = userRepository.findAll();
        long total   = all.size();
        long active  = all.stream().filter(User::isActive).count();
        long interne = all.stream().filter(u ->
                u.getCustomerId() == null ||
                u.getRoles().stream().anyMatch(r -> "ESN".equals(r.getType()))
        ).count();
        long roles = roleRepository.count();
        return new StatsDto(total, active, interne, total - interne, roles);
    }

    // ── POST /api/intra/admin/roles ───────────────────────────────────────────

    @PostMapping("/roles")
    public ResponseEntity<?> createRole(@RequestBody SaveRoleRequest req) {
        if (req.name() == null || req.name().isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Le nom est obligatoire."));
        if (roleRepository.findByName(req.name().trim().toUpperCase()).isPresent())
            return ResponseEntity.badRequest().body(Map.of("error", "Un rôle avec ce nom existe déjà."));
        Role role = new Role(req.name().trim().toUpperCase(),
                req.label() != null ? req.label().trim() : req.name().trim(),
                req.type());
        return ResponseEntity.ok(RoleDto.from(roleRepository.save(role)));
    }

    // ── PUT /api/intra/admin/roles/{id} ───────────────────────────────────────

    @PutMapping("/roles/{id}")
    public ResponseEntity<?> updateRole(@PathVariable Long id, @RequestBody SaveRoleRequest req) {
        return roleRepository.findById(id)
                .<ResponseEntity<?>>map(role -> {
                    if (req.label() != null) role.setLabel(req.label().trim());
                    if (req.type()  != null) role.setType(req.type());
                    return ResponseEntity.ok(RoleDto.from(roleRepository.save(role)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── DELETE /api/intra/admin/roles/{id} ────────────────────────────────────

    @DeleteMapping("/roles/{id}")
    public ResponseEntity<?> deleteRole(@PathVariable Long id) {
        return roleRepository.findById(id)
                .<ResponseEntity<?>>map(role -> {
                    long usersWithRole = userRepository.countByRolesContaining(role);
                    if (usersWithRole > 0)
                        return ResponseEntity.badRequest()
                                .body(Map.of("error",
                                        "Ce rôle est utilisé par " + usersWithRole + " utilisateur(s)."));
                    roleRepository.delete(role);
                    return ResponseEntity.ok().build();
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── GET /api/intra/admin/roles ────────────────────────────────────────────

    @GetMapping("/roles")
    public List<RoleDto> roles() {
        return roleRepository.findAll().stream()
                .sorted(java.util.Comparator.comparing(r -> r.getLabel() != null ? r.getLabel() : r.getName()))
                .map(RoleDto::from)
                .toList();
    }

    // ── PUT /api/intra/admin/users/{id}/roles ─────────────────────────────────

    @PutMapping("/users/{id}/roles")
    public ResponseEntity<?> setRoles(@PathVariable Long id,
                                      @RequestBody SetRolesRequest req,
                                      Authentication auth) {
        // Récupère le tenant de l'admin connecté
        if (!(auth.getPrincipal() instanceof CustomUserDetails currentUser))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        Long currentTenantId = currentUser.getTenant() != null
                ? currentUser.getTenant().getId() : null;

        Map<Long, String> customerNames = customerRepository.findAll().stream()
                .collect(Collectors.toMap(c -> c.getId(),
                        c -> c.getCompanyName() != null ? c.getCompanyName() : ""));

        return userRepository.findById(id)
                .<ResponseEntity<?>>map(u -> {
                    // Vérification du tenant : l'admin ne peut modifier que les users de son tenant
                    Long targetTenantId = u.getTenant() != null ? u.getTenant().getId() : null;
                    if (currentTenantId != null && !currentTenantId.equals(targetTenantId)) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                .body(Map.of("error", "Cet utilisateur appartient à un autre tenant."));
                    }
                    // Remplace tous les rôles
                    u.getRoles().clear();
                    for (String name : req.roleNames()) {
                        roleRepository.findByName(name).ifPresent(u.getRoles()::add);
                    }
                    return ResponseEntity.ok(UserDto.from(userRepository.save(u), customerNames));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── PATCH /api/intra/admin/users/{id}/toggle-active ───────────────────────

    @PatchMapping("/users/{id}/toggle-active")
    public ResponseEntity<UserDto> toggleActive(@PathVariable Long id) {
        Map<Long, String> customerNames = customerRepository.findAll().stream()
                .collect(Collectors.toMap(c -> c.getId(),
                        c -> c.getCompanyName() != null ? c.getCompanyName() : ""));
        final Map<Long, String> names = customerNames;
        return userRepository.findById(id)
                .<ResponseEntity<UserDto>>map(u -> {
                    u.setActive(!u.isActive());
                    return ResponseEntity.ok(UserDto.from(userRepository.save(u), names));
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
