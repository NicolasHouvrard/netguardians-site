package com.logicielpourtous.site.features.ticketing;

import com.logicielpourtous.site.features.request.RequestPriority;
import com.logicielpourtous.site.features.request.RequestStatus;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "ticket_routing_rules")
public class TicketRoutingRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Column(nullable = false)
    private boolean active = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_field", nullable = false, length = 30)
    private MatchField matchField;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_type", nullable = false, length = 20)
    private MatchType matchType;

    @Column(name = "match_value", nullable = false, length = 500)
    private String matchValue;

    @Column(name = "assign_team_id")
    private Long assignTeamId;

    @Column(name = "assign_user_id")
    private Long assignUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "set_status", length = 20)
    private RequestStatus setStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "set_priority", length = 20)
    private RequestPriority setPriority;

    @Column(name = "stop_on_match", nullable = false)
    private boolean stopOnMatch = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public enum MatchField {
        TITLE("Sujet / Titre"),
        SENDER_EMAIL("Email expéditeur"),
        SENDER_DOMAIN("Domaine expéditeur"),
        RECIPIENT_EMAIL("Email destinataire (alias)"),
        RECIPIENT_DOMAIN("Domaine destinataire"),
        DESCRIPTION("Corps du message"),
        CONTACT_NAME("Nom du contact");

        private final String label;
        MatchField(String label) { this.label = label; }
        public String getLabel() { return label; }
    }

    public enum MatchType {
        CONTAINS("contient"),
        NOT_CONTAINS("ne contient pas"),
        STARTS_WITH("commence par"),
        ENDS_WITH("se termine par"),
        EQUALS("est exactement"),
        REGEX("expression régulière");

        private final String label;
        MatchType(String label) { this.label = label; }
        public String getLabel() { return label; }
    }

    // Getters / Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public MatchField getMatchField() { return matchField; }
    public void setMatchField(MatchField matchField) { this.matchField = matchField; }
    public MatchType getMatchType() { return matchType; }
    public void setMatchType(MatchType matchType) { this.matchType = matchType; }
    public String getMatchValue() { return matchValue; }
    public void setMatchValue(String matchValue) { this.matchValue = matchValue; }
    public Long getAssignTeamId() { return assignTeamId; }
    public void setAssignTeamId(Long assignTeamId) { this.assignTeamId = assignTeamId; }
    public Long getAssignUserId() { return assignUserId; }
    public void setAssignUserId(Long assignUserId) { this.assignUserId = assignUserId; }
    public RequestStatus getSetStatus() { return setStatus; }
    public void setSetStatus(RequestStatus setStatus) { this.setStatus = setStatus; }
    public RequestPriority getSetPriority() { return setPriority; }
    public void setSetPriority(RequestPriority setPriority) { this.setPriority = setPriority; }
    public boolean isStopOnMatch() { return stopOnMatch; }
    public void setStopOnMatch(boolean stopOnMatch) { this.stopOnMatch = stopOnMatch; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
