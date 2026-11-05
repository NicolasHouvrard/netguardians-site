package com.logicielpourtous.site.features.api.angular;

import com.logicielpourtous.site.features.intervention.Intervention;
import com.logicielpourtous.site.features.request.Comment;
import com.logicielpourtous.site.features.request.Request;
import com.logicielpourtous.site.features.request.TicketAttachment;

import java.time.LocalDateTime;
import java.util.List;

public record TicketDto(
        Long          id,
        String        title,
        String        description,
        String        status,
        String        statusLabel,
        String        priority,
        String        priorityLabel,
        String        type,
        String        typeLabel,
        LocalDateTime createdAt,
        String        senderEmail,
        // Client
        String        customerName,
        Long          customerId,
        // Contact
        String        contactName,
        Long          contactId,
        String        contactEmail,
        // Site
        String        siteName,
        Long          siteId,
        // Assignation
        String        assignedToName,
        Long          assignedToId,
        String        teamName,
        Long          teamId,
        // Timeline
        List<CommentDto>      comments,
        // Pièces jointes
        List<AttachmentDto>   attachments,
        // Interventions liées
        List<InterventionRefDto> interventions
) {
    public record CommentDto(
            Long id, String content, boolean isPublic,
            String authorName, LocalDateTime createdAt, String sourceEmail
    ) {
        public static CommentDto from(Comment c) {
            return new CommentDto(
                    c.getId(), c.getContent(), c.isPublic(),
                    c.getAuthor() != null ? c.getAuthor().getFullName() : null,
                    c.getCreatedAt(), c.getSourceEmail());
        }
    }

    public record AttachmentDto(
            Long id, String url, String originalName,
            String contentType, String displaySize, boolean image,
            String uploadedByName, LocalDateTime uploadedAt
    ) {
        public static AttachmentDto from(TicketAttachment a) {
            return new AttachmentDto(
                    a.getId(), a.getFilename(), a.getOriginalName(),
                    a.getContentType(), a.getDisplaySize(), a.isImage(),
                    a.getUploadedBy() != null ? a.getUploadedBy().getFullName() : null,
                    a.getUploadedAt());
        }
    }

    public record InterventionRefDto(
            Long id, String reference, String title,
            String status, String statusLabel, LocalDateTime scheduledStart
    ) {
        public static InterventionRefDto from(Intervention i) {
            return new InterventionRefDto(
                    i.getId(), i.getReference(), i.getTitle(),
                    i.getStatus() != null ? i.getStatus().name()  : null,
                    i.getStatus() != null ? i.getStatus().label   : null,
                    i.getScheduledStart());
        }
    }

    /** Version liste : sans pièces jointes ni interventions (allègement). */
    public static TicketDto from(Request r) {
        return from(r, List.of(), List.of());
    }

    /** Version détail complète. */
    public static TicketDto from(Request r,
                                 List<TicketAttachment> attachments,
                                 List<Intervention> interventions) {
        return new TicketDto(
                r.getId(),
                r.getTitle(),
                r.getDescription(),
                r.getStatus()   != null ? r.getStatus().name()             : null,
                r.getStatus()   != null ? r.getStatus().getLabel()         : null,
                r.getPriority() != null ? r.getPriority().name()           : null,
                r.getPriority() != null ? r.getPriority().getDisplayValue(): null,
                r.getType()     != null ? r.getType().name()               : null,
                r.getType()     != null ? r.getType().getLabel()           : null,
                r.getCreatedAt(),
                r.getSenderEmail(),
                r.getCustomer()     != null ? r.getCustomer().getCompanyName()    : null,
                r.getCustomer()     != null ? r.getCustomer().getId()             : null,
                r.getContact()      != null ? r.getContact().getFullName()        : null,
                r.getContact()      != null ? r.getContact().getId()              : null,
                r.getContact()      != null ? r.getContact().getEmail()           : null,
                r.getSite()         != null ? r.getSite().getName()               : null,
                r.getSite()         != null ? r.getSite().getId()                 : null,
                r.getAssignedTo()   != null ? r.getAssignedTo().getFullName()     : null,
                r.getAssignedTo()   != null ? r.getAssignedTo().getId()           : null,
                r.getAssignedTeam() != null ? r.getAssignedTeam().getName()       : null,
                r.getAssignedTeam() != null ? r.getAssignedTeam().getId()         : null,
                r.getComments() != null
                        ? r.getComments().stream()
                                .sorted(java.util.Comparator.comparing(Comment::getCreatedAt).reversed())
                                .map(CommentDto::from).toList()
                        : List.of(),
                attachments != null
                        ? attachments.stream().map(AttachmentDto::from).toList()
                        : List.of(),
                interventions != null
                        ? interventions.stream().map(InterventionRefDto::from).toList()
                        : List.of()
        );
    }
}
