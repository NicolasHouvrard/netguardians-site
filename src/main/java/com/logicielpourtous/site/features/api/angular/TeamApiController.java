package com.logicielpourtous.site.features.api.angular;

import com.logicielpourtous.site.features.request.RequestType;
import com.logicielpourtous.site.features.team.Team;
import com.logicielpourtous.site.features.team.TeamRepository;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/intra/admin/teams")
@PreAuthorize("hasRole('ADMIN')")
public class TeamApiController {

    private final TeamRepository teamRepo;
    private final UserRepository userRepo;

    public TeamApiController(TeamRepository teamRepo, UserRepository userRepo) {
        this.teamRepo = teamRepo;
        this.userRepo = userRepo;
    }

    // ── Types de demande ──────────────────────────────────────────────────────

    @GetMapping("/request-types")
    public List<Map<String, String>> requestTypes() {
        return Arrays.stream(RequestType.values()).map(rt -> Map.of(
                "name",  rt.name(),
                "label", rt.getLabel()
        )).toList();
    }

    // ── Utilisateurs internes ─────────────────────────────────────────────────

    @GetMapping("/users")
    public List<Map<String, Object>> users() {
        return userRepo.findInternalUsers().stream().map(u -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",       u.getId());
            m.put("fullName", u.getFullName());
            m.put("email",    u.getEmail());
            return m;
        }).toList();
    }

    // ── Liste des équipes ─────────────────────────────────────────────────────

    @GetMapping
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        List<Team> teams = teamRepo.findAllWithMembers();
        teams.forEach(t -> t.getRequestTypes().size());
        return teams.stream().map(this::toMap).toList();
    }

    // ── Création ──────────────────────────────────────────────────────────────

    @PostMapping
    @Transactional
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        Team team = new Team();
        return ResponseEntity.ok(toMap(persist(team, body)));
    }

    // ── Mise à jour ───────────────────────────────────────────────────────────

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<Map<String, Object>> update(@PathVariable Long id,
                                                       @RequestBody Map<String, Object> body) {
        return teamRepo.findByIdWithMembers(id).map(team -> {
            team.getRequestTypes().size();
            return ResponseEntity.ok(toMap(persist(team, body)));
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── Suppression ───────────────────────────────────────────────────────────

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!teamRepo.existsById(id)) return ResponseEntity.<Void>notFound().build();
        teamRepo.deleteById(id);
        return ResponseEntity.<Void>ok().build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Team persist(Team team, Map<String, Object> body) {
        team.setName((String) body.get("name"));
        String desc = (String) body.get("description");
        team.setDescription(desc != null && !desc.isBlank() ? desc : null);

        Set<User> members = new HashSet<>();
        List<Integer> memberIds = (List<Integer>) body.getOrDefault("memberIds", List.of());
        memberIds.forEach(uid -> userRepo.findById(uid.longValue()).ifPresent(members::add));
        team.setMembers(members);

        Set<RequestType> types = new HashSet<>();
        List<String> typeNames = (List<String>) body.getOrDefault("requestTypes", List.of());
        for (String t : typeNames) {
            try { types.add(RequestType.valueOf(t)); } catch (IllegalArgumentException ignored) {}
        }
        team.setRequestTypes(types);

        return teamRepo.save(team);
    }

    private Map<String, Object> toMap(Team t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",          t.getId());
        m.put("name",        t.getName());
        m.put("description", t.getDescription());
        m.put("members", t.getMembers().stream()
                .sorted(Comparator.comparing(u -> u.getFullName() != null ? u.getFullName() : ""))
                .map(u -> Map.of("id", u.getId(), "fullName",
                        u.getFullName() != null ? u.getFullName() : u.getEmail()))
                .toList());
        m.put("requestTypes", t.getRequestTypes().stream().map(rt ->
                Map.of("name", rt.name(), "label", rt.getLabel())).toList());
        return m;
    }
}
