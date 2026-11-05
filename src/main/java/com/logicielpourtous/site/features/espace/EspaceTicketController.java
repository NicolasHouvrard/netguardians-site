package com.logicielpourtous.site.features.espace;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.request.*;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.UUID;

@Controller
@RequestMapping("/espace/tickets")
public class EspaceTicketController {

    private final RequestRepository          requestRepository;
    private final UserRepository             userRepository;
    private final CommentRepository          commentRepository;
    private final TicketAttachmentRepository attachmentRepository;
    private final EspaceContextService       ctx;

    @Value("${app.upload-dir}")
    private String uploadDir;

    public EspaceTicketController(RequestRepository requestRepository,
                                  UserRepository userRepository,
                                  CommentRepository commentRepository,
                                  TicketAttachmentRepository attachmentRepository,
                                  EspaceContextService ctx) {
        this.requestRepository    = requestRepository;
        this.userRepository       = userRepository;
        this.commentRepository    = commentRepository;
        this.attachmentRepository = attachmentRepository;
        this.ctx                  = ctx;
    }

    @GetMapping
    public String list(@AuthenticationPrincipal CustomUserDetails userDetails,
                       HttpSession session, Model model) {
        Customer customer = ctx.loadCustomer(userDetails, session);
        model.addAttribute("customer", customer);
        model.addAttribute("requests", requestRepository.findByCustomerIdOrderByCreatedAtDesc(customer.getId()));
        return "espace/tickets";
    }

    @GetMapping("/new")
    public String newForm(@AuthenticationPrincipal CustomUserDetails userDetails,
                          HttpSession session, Model model) {
        model.addAttribute("customer",     ctx.loadCustomer(userDetails, session));
        model.addAttribute("requestTypes", RequestType.values());
        model.addAttribute("request",      new Request());
        return "espace/ticket-new";
    }

    @PostMapping("/new")
    public String create(@AuthenticationPrincipal CustomUserDetails userDetails,
                         HttpSession session,
                         @RequestParam String title,
                         @RequestParam(required = false) RequestType type,
                         @RequestParam(required = false) RequestPriority priority,
                         @RequestParam(required = false) String description,
                         @RequestParam(required = false) MultipartFile file,
                         RedirectAttributes ra) {
        Customer customer = ctx.loadCustomer(userDetails, session);
        Request r = new Request();
        r.setCustomer(customer);
        r.setTitle(title);
        r.setType(type);
        r.setPriority(priority != null ? priority : RequestPriority.MEDIUM);
        r.setDescription(description);
        r.setStatus(RequestStatus.NEW);
        userRepository.findByEmail(userDetails.getUsername()).ifPresent(r::setCreatedBy);
        r.registerChange();
        requestRepository.save(r);

        if (file != null && !file.isEmpty()) {
            try {
                String orig   = file.getOriginalFilename();
                String ext    = (orig != null && orig.contains(".")) ? orig.substring(orig.lastIndexOf('.')) : "";
                String stored = UUID.randomUUID() + ext;
                Path dir = Paths.get(uploadDir, "tickets", String.valueOf(r.getId()));
                Files.createDirectories(dir);
                Files.copy(file.getInputStream(), dir.resolve(stored));

                TicketAttachment att = new TicketAttachment();
                att.setRequest(r);
                att.setFilename("/uploads/tickets/" + r.getId() + "/" + stored);
                att.setOriginalName(orig);
                att.setContentType(file.getContentType());
                att.setFileSize(file.getSize());
                userRepository.findByEmail(userDetails.getUsername()).ifPresent(att::setUploadedBy);
                attachmentRepository.save(att);
            } catch (Exception ignored) {}
        }

        ra.addFlashAttribute("successMessage", "Votre demande a bien été enregistrée.");
        return "redirect:/espace/tickets/" + r.getId();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public String detail(@PathVariable Long id,
                         @AuthenticationPrincipal CustomUserDetails userDetails,
                         HttpSession session, Model model) {
        Customer customer = ctx.loadCustomer(userDetails, session);
        Request request = requestRepository.findById(id)
                .filter(r -> r.getCustomer().getId().equals(customer.getId()))
                .orElseThrow(() -> new IllegalArgumentException("Ticket introuvable"));

        request.getTimeEntries().forEach(te -> te.getAuthor().getFullName());
        var timeline = new ArrayList<TimelineEvent>();
        request.getComments().stream()
                .filter(Comment::isPublic)
                .forEach(c -> timeline.add(new TimelineEvent(c.getCreatedAt(), "comment", c, null)));
        request.getTimeEntries().forEach(te -> {
            LocalDateTime ts = te.getStartTime() != null
                    ? LocalDateTime.of(te.getDate(), te.getStartTime())
                    : te.getDate().atStartOfDay();
            timeline.add(new TimelineEvent(ts, "timeentry", null, te));
        });
        timeline.sort(Comparator.comparing(TimelineEvent::when).reversed());

        var attachments = attachmentRepository.findByRequestIdOrderByUploadedAtDesc(id);
        attachments.forEach(a -> { if (a.getUploadedBy() != null) a.getUploadedBy().getFullName(); });

        model.addAttribute("customer",         customer);
        model.addAttribute("request",          request);
        model.addAttribute("timeline",         timeline);
        model.addAttribute("attachments",      attachments);
        model.addAttribute("currentUserEmail", userDetails.getUsername());
        return "espace/ticket-detail";
    }

    @PostMapping("/{id}/comment")
    public String addComment(@PathVariable Long id,
                             @AuthenticationPrincipal CustomUserDetails userDetails,
                             HttpSession session,
                             @RequestParam String content,
                             RedirectAttributes ra) {
        Customer customer = ctx.loadCustomer(userDetails, session);
        Request request = requestRepository.findById(id)
                .filter(r -> r.getCustomer().getId().equals(customer.getId()))
                .orElseThrow(() -> new IllegalArgumentException("Ticket introuvable"));
        User author = userRepository.findByEmail(userDetails.getUsername()).orElseThrow();

        Comment comment = new Comment();
        comment.setContent(content);
        comment.setPublic(true);
        comment.setAuthor(author);
        comment.setRequest(request);
        comment.registerAdded();
        commentRepository.save(comment);

        ra.addFlashAttribute("successMessage", "Commentaire ajouté.");
        return "redirect:/espace/tickets/" + id;
    }

    @PostMapping("/{id}/attachments")
    public String uploadAttachment(@PathVariable Long id,
                                   @RequestParam("file") MultipartFile file,
                                   @AuthenticationPrincipal CustomUserDetails userDetails,
                                   HttpSession session,
                                   RedirectAttributes ra) {
        if (file.isEmpty()) {
            ra.addFlashAttribute("errorMessage", "Fichier vide.");
            return "redirect:/espace/tickets/" + id;
        }
        Customer customer = ctx.loadCustomer(userDetails, session);
        Request request = requestRepository.findById(id)
                .filter(r -> r.getCustomer().getId().equals(customer.getId()))
                .orElseThrow(() -> new IllegalArgumentException("Ticket introuvable"));
        try {
            String orig   = file.getOriginalFilename();
            String ext    = (orig != null && orig.contains(".")) ? orig.substring(orig.lastIndexOf('.')) : "";
            String stored = UUID.randomUUID() + ext;
            Path dir = Paths.get(uploadDir, "tickets", String.valueOf(id));
            Files.createDirectories(dir);
            Files.copy(file.getInputStream(), dir.resolve(stored));

            TicketAttachment att = new TicketAttachment();
            att.setRequest(request);
            att.setFilename("/uploads/tickets/" + id + "/" + stored);
            att.setOriginalName(orig);
            att.setContentType(file.getContentType());
            att.setFileSize(file.getSize());
            userRepository.findByEmail(userDetails.getUsername()).ifPresent(att::setUploadedBy);
            attachmentRepository.save(att);

            ra.addFlashAttribute("successMessage", "Fichier ajouté.");
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage", "Erreur lors de l'upload : " + e.getMessage());
        }
        return "redirect:/espace/tickets/" + id;
    }

    @PostMapping("/{id}/attachments/{attachId}/delete")
    public String deleteAttachment(@PathVariable Long id,
                                   @PathVariable Long attachId,
                                   @AuthenticationPrincipal CustomUserDetails userDetails,
                                   HttpSession session,
                                   RedirectAttributes ra) {
        Customer customer = ctx.loadCustomer(userDetails, session);
        requestRepository.findById(id)
                .filter(r -> r.getCustomer().getId().equals(customer.getId()))
                .ifPresent(r -> attachmentRepository.findById(attachId)
                        .filter(a -> a.getRequest().getId().equals(id))
                        .filter(a -> a.getUploadedBy() != null
                                && a.getUploadedBy().getEmail().equals(userDetails.getUsername()))
                        .ifPresent(a -> {
                            try {
                                Path f = Paths.get(uploadDir, a.getFilename().replaceFirst("^/uploads/", ""));
                                Files.deleteIfExists(f);
                            } catch (Exception ignored) {}
                            attachmentRepository.delete(a);
                        }));
        ra.addFlashAttribute("successMessage", "Pièce jointe supprimée.");
        return "redirect:/espace/tickets/" + id;
    }
}
