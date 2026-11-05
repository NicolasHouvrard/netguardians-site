package com.logicielpourtous.site.features.request;

import java.time.LocalDateTime;

import com.logicielpourtous.site.features.commun.Syncable;
import com.logicielpourtous.site.features.customers.Contact;
import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.customers.Site;
import com.logicielpourtous.site.features.subscription.Subscription;
import com.logicielpourtous.site.features.team.Team;
import com.logicielpourtous.site.features.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.CascadeType;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.ArrayList;

@Entity
@Table(name = "requests")
@Getter @Setter
public class Request extends Syncable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title; // Objet de la demande

    @Column(columnDefinition = "TEXT")
    private String description;

    private String senderEmail;
    private String recipientEmail;
    private LocalDateTime sentAt;

    private LocalDateTime createdAt = LocalDateTime.now();
    
    @Enumerated(EnumType.STRING)
    private RequestStatus status = RequestStatus.NEW;

    @ManyToOne
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @ManyToOne
    @JoinColumn(name = "contact_id")
    private Contact contact;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "site_id")
    private Site site;

    @Enumerated(EnumType.STRING)
    private RequestPriority priority = RequestPriority.MEDIUM; // Par défaut

    @Enumerated(EnumType.STRING)
    private RequestType type;


    // --- AJOUTE CES DEUX MÉTHODES ---
    public RequestType getType() {
        return type;
    }

    public void setType(RequestType type) {
        this.type = type;
    }
    
   
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_id")
    private Subscription subscription;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id")
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to_user_id")
    private User assignedTo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_team_id")
    private Team assignedTeam;

    @OneToMany(mappedBy = "request", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt DESC")
    private List<Comment> comments = new ArrayList<>();

    private Integer estimatedMinutes;

    /**
     * Assignation recue par synchronisation (cote serveur secondaire) : nom de l'equipe / du technicien
     * du serveur maitre, en TEXTE (ces {@code Team}/{@code User} n'existent pas ici, pas de relation).
     * Jamais renseignes cote maitre, ou l'assignation reelle est {@link #assignedTeam}/{@link #assignedTo}.
     */
    private String syncAssignedTeamName;

    private String syncAssignedToName;

    @OneToMany(mappedBy = "request", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("date DESC, createdAt DESC")
    private List<TimeEntry> timeEntries = new ArrayList<>();

    public int getTotalSpentMinutes() {
        return timeEntries.stream().mapToInt(e -> e.getDurationMinutes() != null ? e.getDurationMinutes() : 0).sum();
    }

    public String getFormattedEstimated() {
        return formatMinutes(estimatedMinutes);
    }

    public String getFormattedSpent() {
        return formatMinutes(getTotalSpentMinutes());
    }

    private static String formatMinutes(Integer minutes) {
        if (minutes == null || minutes == 0) return "—";
        int h = minutes / 60;
        int m = minutes % 60;
        if (h == 0) return m + "min";
        if (m == 0) return h + "h";
        return h + "h" + String.format("%02d", m);
    }

    /**
     * Enregistre un {@link RequestChangedEvent} qui sera publie par Spring Data au prochain
     * {@code requestRepository.save(this)}. A appeler juste AVANT ce {@code save()}, apres
     * toute mutation significative (creation, statut, assignation equipe/technicien/type...).
     *
     * <p>Publique car {@code AbstractAggregateRoot.registerEvent(...)} est {@code protected}
     * (inaccessible depuis les controleurs). Ne PAS appeler lors de l'application d'un
     * changement recu par synchronisation (prevention d'echo, Task 4).
     */
    public void registerChange() {
        registerEvent(new RequestChangedEvent(this));
    }

    /**
     * Nom d'affichage de l'equipe assignee ({@code null} si aucune), pour les projections qui
     * aplatissent les references non-{@code Syncable} en texte (DTO de synchronisation) sans
     * dependre du module {@code team}. Pas de prefixe {@code get} : volontairement hors des
     * proprietes bean (Jackson/Thymeleaf), comme le reste n'est expose que via Lombok.
     */
    public String assignedTeamDisplayName() {
        return assignedTeam != null ? assignedTeam.getName() : null;
    }

    /**
     * Id du client rattache ({@code null} si aucun), pour le module {@code sync} (routage et controle
     * d'acces par serveur secondaire) sans dependance au module {@code customers}. Pas de prefixe
     * {@code get} : hors proprietes bean, comme ci-dessus. Sur un proxy {@code Customer}, {@code getId()}
     * ne declenche pas d'initialisation.
     */
    public Long customerId() {
        return customer != null ? customer.getId() : null;
    }

    /** Nom d'affichage du technicien assigne ({@code null} si aucun) — meme usage que ci-dessus. */
    public String assignedToDisplayName() {
        return assignedTo != null ? assignedTo.getFullName() : null;
    }
}
