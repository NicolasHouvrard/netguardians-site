package com.logicielpourtous.site.features.api.angular;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.customers.ContactRepository;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.customers.SiteRepository;
import com.logicielpourtous.site.features.intervention.InterventionRepository;
import com.logicielpourtous.site.features.request.*;
import com.logicielpourtous.site.features.team.TeamRepository;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/intra/tickets")
public class TicketApiController {

    private final RequestRepository          requestRepository;
    private final CommentRepository          commentRepository;
    private final CustomerRepository         customerRepository;
    private final ContactRepository          contactRepository;
    private final SiteRepository             siteRepository;
    private final UserRepository             userRepository;
    private final TeamRepository             teamRepository;
    private final TicketAttachmentRepository attachmentRepository;
    private final InterventionRepository     interventionRepository;

    @Value("${app.upload-dir:uploads}")
    private String uploadDir;

    public TicketApiController(RequestRepository requestRepository,
                               CommentRepository commentRepository,
                               CustomerRepository customerRepository,
                               ContactRepository contactRepository,
                               SiteRepository siteRepository,
                               UserRepository userRepository,
                               TeamRepository teamRepository,
                               TicketAttachmentRepository attachmentRepository,
                               InterventionRepository interventionRepository) {
        this.requestRepository      = requestRepository;
        this.commentRepository      = commentRepository;
        this.customerRepository     = customerRepository;
        this.contactRepository      = contactRepository;
        this.siteRepository         = siteRepository;
        this.userRepository         = userRepository;
        this.teamRepository         = teamRepository;
        this.attachmentRepository   = attachmentRepository;
        this.interventionRepository = interventionRepository;
    }

    // ── Liste paginée ─────────────────────────────────────────────────────────

    public record TicketPageResponse(
            List<TicketDto> content,
            int  page, int  size,
            long totalElements, int totalPages
    ) {}

    @GetMapping
    public TicketPageResponse list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) Long   customerId,
            @RequestParam(required = false) Long   assignedToId,
            @RequestParam(required = false) Long   teamId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal CustomUserDetails current) {

        Specification<Request> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status     != null && !status.isBlank()) {
                try { predicates.add(cb.equal(root.get("status"), RequestStatus.valueOf(status))); }
                catch (IllegalArgumentException ignored) {}
            }
            if (priority   != null && !priority.isBlank()) {
                try { predicates.add(cb.equal(root.get("priority"), RequestPriority.valueOf(priority))); }
                catch (IllegalArgumentException ignored) {}
            }
            if (customerId   != null) predicates.add(cb.equal(root.get("customer").get("id"), customerId));
            if (assignedToId != null) predicates.add(cb.equal(root.get("assignedTo").get("id"), assignedToId));
            if (teamId       != null) predicates.add(cb.equal(root.get("assignedTeam").get("id"), teamId));
            if (q != null && !q.isBlank()) {
                String pat = "%" + q.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("title")), pat),
                        cb.like(cb.lower(root.get("description")), pat)
                ));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Pageable pageable = PageRequest.of(page, Math.min(size, 100),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Request> result = requestRepository.findAll(spec, pageable);

        return new TicketPageResponse(
                result.getContent().stream().map(TicketDto::from).toList(),
                result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages()
        );
    }

    // ── Détail ────────────────────────────────────────────────────────────────

    @GetMapping("/{id}")
    public ResponseEntity<TicketDto> detail(@PathVariable Long id) {
        return requestRepository.findById(id)
                .map(r -> ResponseEntity.ok(TicketDto.from(
                        r,
                        attachmentRepository.findByRequestIdOrderByUploadedAtDesc(id),
                        interventionRepository.findByRequestIdOrderByCreatedAtDesc(id))))
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Création ──────────────────────────────────────────────────────────────

    public record CreateTicketRequest(
            String title, String description,
            String priority, String type,
            Long customerId, Long contactId, Long siteId,
            Long assignedToId, Long teamId
    ) {}

    @PostMapping
    public ResponseEntity<TicketDto> create(@RequestBody CreateTicketRequest req,
                                            Principal principal) {
        Request ticket = new Request();
        ticket.setTitle(req.title());
        ticket.setDescription(req.description());
        ticket.setStatus(RequestStatus.NEW);
        ticket.setPriority(req.priority() != null
                ? RequestPriority.valueOf(req.priority()) : RequestPriority.MEDIUM);
        if (req.type() != null) {
            try { ticket.setType(RequestType.valueOf(req.type())); } catch (IllegalArgumentException ignored) {}
        }
        if (req.customerId()   != null) customerRepository.findById(req.customerId()).ifPresent(ticket::setCustomer);
        if (req.contactId()    != null) contactRepository.findById(req.contactId()).ifPresent(ticket::setContact);
        if (req.siteId()       != null) siteRepository.findById(req.siteId()).ifPresent(ticket::setSite);
        if (req.assignedToId() != null) userRepository.findById(req.assignedToId()).ifPresent(ticket::setAssignedTo);
        if (req.teamId()       != null) teamRepository.findById(req.teamId()).ifPresent(ticket::setAssignedTeam);
        if (principal != null)          userRepository.findByEmail(principal.getName()).ifPresent(ticket::setCreatedBy);

        ticket.registerChange();
        return ResponseEntity.ok(TicketDto.from(requestRepository.save(ticket)));
    }

    // ── Changement de statut ──────────────────────────────────────────────────

    @PatchMapping("/{id}/status")
    public ResponseEntity<TicketDto> updateStatus(@PathVariable Long id,
                                                  @RequestBody Map<String, String> body,
                                                  Principal principal) {
        return requestRepository.findById(id)
                .<ResponseEntity<TicketDto>>map(ticket -> {
                    try {
                        RequestStatus newStatus = RequestStatus.valueOf(body.get("status"));
                        RequestStatus old = ticket.getStatus();
                        ticket.setStatus(newStatus);
                        ticket.registerChange();
                        requestRepository.save(ticket);
                        // Log automatique (commentaire public)
                        logActivity(ticket, principal,
                                "Statut changé : " + (old != null ? old.getLabel() : "?")
                                + " → " + newStatus.getLabel());
                        return ResponseEntity.ok(TicketDto.from(ticket));
                    } catch (IllegalArgumentException e) {
                        return ResponseEntity.badRequest().build();
                    }
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ── Assignation (client / contact / site / équipe / technicien) ───────────

    public record AssignRequest(
            Long customerId, Long contactId, Long siteId,
            Long assignedToId, Long teamId
    ) {}

    @PatchMapping("/{id}/assign")
    public ResponseEntity<TicketDto> assign(@PathVariable Long id,
                                            @RequestBody AssignRequest req,
                                            Principal principal) {
        return requestRepository.findById(id)
                .<ResponseEntity<TicketDto>>map(ticket -> {
                    if (req.customerId()   != null) customerRepository.findById(req.customerId()).ifPresent(ticket::setCustomer);
                    if (req.contactId()    != null) contactRepository.findById(req.contactId()).ifPresent(ticket::setContact);
                    else if (req.contactId() == null && req.customerId() != null) ticket.setContact(null);
                    if (req.siteId()       != null) siteRepository.findById(req.siteId()).ifPresent(ticket::setSite);
                    if (req.assignedToId() != null) userRepository.findById(req.assignedToId()).ifPresent(ticket::setAssignedTo);
                    else if (req.assignedToId() == null) ticket.setAssignedTo(null);
                    if (req.teamId()       != null) teamRepository.findById(req.teamId()).ifPresent(ticket::setAssignedTeam);
                    else if (req.teamId() == null) ticket.setAssignedTeam(null);
                    ticket.registerChange();
                    requestRepository.save(ticket);
                    return ResponseEntity.ok(TicketDto.from(ticket));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ── Commentaires ──────────────────────────────────────────────────────────

    public record AddCommentRequest(String content, boolean isPublic) {}

    @PostMapping("/{id}/comment")
    public ResponseEntity<TicketDto> addComment(@PathVariable Long id,
                                                @RequestBody AddCommentRequest req,
                                                @AuthenticationPrincipal CustomUserDetails principal) {
        return requestRepository.findById(id)
                .<ResponseEntity<TicketDto>>map(ticket -> {
                    Comment comment = new Comment();
                    comment.setContent(req.content());
                    comment.setPublic(req.isPublic());
                    comment.setRequest(ticket);
                    if (principal != null)
                        userRepository.findById(principal.getUserId()).ifPresent(comment::setAuthor);
                    comment.registerAdded();
                    commentRepository.save(comment);
                    return ResponseEntity.ok(TicketDto.from(requestRepository.findById(id).orElse(ticket)));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ── Métadonnées formulaire (clients, équipes, utilisateurs) ──────────────

    @GetMapping("/form-meta")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Map<String, Object> formMeta() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("statuses",   List.of(RequestStatus.values()).stream()
                .map(s -> Map.of("value", s.name(), "label", s.getLabel())).toList());
        m.put("priorities", List.of(RequestPriority.values()).stream()
                .map(p -> Map.of("value", p.name(), "label", p.getDisplayValue())).toList());
        m.put("types",      List.of(RequestType.values()).stream()
                .map(t -> Map.of("value", t.name(), "label", t.getLabel())).toList());
        m.put("customers",  customerRepository.findAll().stream()
                .map(c -> Map.of("id", c.getId(), "name", c.getCompanyName())).toList());
        // Équipes avec leurs membres (pour la cascade équipe → technicien)
        m.put("teams",      teamRepository.findAllWithMembers().stream()
                .map(t -> {
                    Map<String, Object> tm = new LinkedHashMap<>();
                    tm.put("id",   t.getId());
                    tm.put("name", t.getName());
                    tm.put("memberIds", t.getMembers().stream()
                            .map(User::getId).sorted().toList());
                    return tm;
                }).toList());
        m.put("users",      userRepository.findInternalUsers().stream()
                .sorted(Comparator.comparing(u -> u.getFullName() != null ? u.getFullName() : ""))
                .map(u -> Map.of("id", u.getId(), "fullName",
                        u.getFullName() != null ? u.getFullName() : u.getEmail())).toList());
        return m;
    }

    /** Sites pour un client donné. */
    @GetMapping("/customers/{customerId}/sites")
    public List<Map<String, Object>> sites(@PathVariable Long customerId) {
        return siteRepository.findByCustomerId(customerId).stream()
                .map(s -> Map.<String, Object>of("id", s.getId(), "name", s.getName()))
                .toList();
    }

    /** Contacts pour un client (et optionnellement un site). */
    @GetMapping("/customers/{customerId}/contacts")
    public List<Map<String, Object>> contacts(@PathVariable Long customerId,
                                               @RequestParam(required = false) Long siteId) {
        return contactRepository.search(customerId, siteId, "").stream()
                .map(c -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id",       c.getId());
                    map.put("fullName", c.getFullName() != null ? c.getFullName() : c.getEmail());
                    map.put("email",    c.getEmail());
                    return map;
                })
                .toList();
    }

    // ── Méta (statuts/priorités/types) ────────────────────────────────────────

    @GetMapping("/meta")
    public Map<String, Object> meta() {
        return Map.of(
                "statuses",   List.of(RequestStatus.values()).stream()
                        .map(s -> Map.of("value", s.name(), "label", s.getLabel())).toList(),
                "priorities", List.of(RequestPriority.values()).stream()
                        .map(p -> Map.of("value", p.name(), "label", p.getDisplayValue())).toList(),
                "types",      List.of(RequestType.values()).stream()
                        .map(t -> Map.of("value", t.name(), "label", t.getLabel())).toList()
        );
    }

    // ── Pièces jointes ────────────────────────────────────────────────────────

    @PostMapping("/{id}/attachments")
    public ResponseEntity<TicketDto> uploadAttachment(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal CustomUserDetails principal) {

        Request ticket = requestRepository.findById(id).orElse(null);
        if (ticket == null) return ResponseEntity.notFound().build();
        if (file.isEmpty()) return ResponseEntity.badRequest().build();

        try {
            String orig = file.getOriginalFilename();
            String ext  = (orig != null && orig.contains("."))
                    ? orig.substring(orig.lastIndexOf('.')) : "";
            String stored = UUID.randomUUID() + ext;
            Path dir = Paths.get(uploadDir, "tickets", String.valueOf(id));
            Files.createDirectories(dir);
            Files.copy(file.getInputStream(), dir.resolve(stored));

            TicketAttachment att = new TicketAttachment();
            att.setRequest(ticket);
            att.setFilename("/uploads/tickets/" + id + "/" + stored);
            att.setOriginalName(orig);
            att.setContentType(file.getContentType());
            att.setFileSize(file.getSize());
            if (principal != null)
                userRepository.findById(principal.getUserId()).ifPresent(att::setUploadedBy);
            attachmentRepository.save(att);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
        return detail(id);
    }

    @DeleteMapping("/{id}/attachments/{attachId}")
    public ResponseEntity<TicketDto> deleteAttachment(@PathVariable Long id,
                                                      @PathVariable Long attachId) {
        attachmentRepository.findById(attachId)
                .filter(a -> a.getRequest().getId().equals(id))
                .ifPresent(a -> {
                    try {
                        Path f = Paths.get(uploadDir,
                                a.getFilename().replaceFirst("^/uploads/", ""));
                        Files.deleteIfExists(f);
                    } catch (Exception ignored) {}
                    attachmentRepository.delete(a);
                });
        return detail(id);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void logActivity(Request ticket, Principal principal, String text) {
        try {
            Comment log = new Comment();
            log.setContent(text);
            log.setPublic(false);
            log.setRequest(ticket);
            if (principal != null)
                userRepository.findByEmail(principal.getName()).ifPresent(log::setAuthor);
            log.registerAdded();
            commentRepository.save(log);
        } catch (Exception ignored) {}
    }
}
