package com.logicielpourtous.site.features.ticketing;

import com.logicielpourtous.site.features.request.Request;
import com.logicielpourtous.site.features.request.RequestRepository;
import com.logicielpourtous.site.features.team.Team;
import com.logicielpourtous.site.features.team.TeamRepository;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Service
public class TicketRoutingService {

    private static final Logger log = LoggerFactory.getLogger(TicketRoutingService.class);

    private final TicketRoutingRuleRepository ruleRepo;
    private final RequestRepository           requestRepo;
    private final TeamRepository              teamRepo;
    private final UserRepository              userRepo;

    public TicketRoutingService(TicketRoutingRuleRepository ruleRepo,
                                 RequestRepository requestRepo,
                                 TeamRepository teamRepo,
                                 UserRepository userRepo) {
        this.ruleRepo    = ruleRepo;
        this.requestRepo = requestRepo;
        this.teamRepo    = teamRepo;
        this.userRepo    = userRepo;
    }

    /**
     * Évalue les règles actives dans l'ordre et applique la/les première(s) correspondante(s).
     * @param request     ticket nouvellement créé
     * @param fromAddress email expéditeur (peut être null si création manuelle)
     */
    @Transactional
    public void applyRules(Request request, String fromAddress) {
        List<TicketRoutingRule> rules = ruleRepo.findByActiveTrueOrderBySortOrderAsc();
        boolean modified = false;

        for (TicketRoutingRule rule : rules) {
            if (matches(rule, request, fromAddress)) {
                log.info("Routing rule '{}' matched ticket #{}", rule.getName(), request.getId());
                applyRule(rule, request);
                modified = true;
                if (rule.isStopOnMatch()) break;
            }
        }

        if (modified) {
            request.registerChange();
            requestRepo.save(request);
        }
    }

    private boolean matches(TicketRoutingRule rule, Request request, String fromAddress) {
        String subject = getValue(rule.getMatchField(), request, fromAddress);
        if (subject == null) return false;

        String val = rule.getMatchValue();
        return switch (rule.getMatchType()) {
            case CONTAINS     ->  subject.toLowerCase().contains(val.toLowerCase());
            case NOT_CONTAINS -> !subject.toLowerCase().contains(val.toLowerCase());
            case STARTS_WITH  ->  subject.toLowerCase().startsWith(val.toLowerCase());
            case ENDS_WITH    ->  subject.toLowerCase().endsWith(val.toLowerCase());
            case EQUALS       ->  subject.equalsIgnoreCase(val);
            case REGEX        ->  matchesRegex(subject, val);
        };
    }

    private String getValue(TicketRoutingRule.MatchField field, Request request, String fromAddress) {
        String recipient = request.getRecipientEmail();
        return switch (field) {
            case TITLE        -> request.getTitle();
            case DESCRIPTION  -> request.getDescription();
            case SENDER_EMAIL -> fromAddress;
            case SENDER_DOMAIN -> fromAddress != null && fromAddress.contains("@")
                    ? fromAddress.substring(fromAddress.indexOf('@') + 1)
                    : null;
            case RECIPIENT_EMAIL  -> recipient;
            case RECIPIENT_DOMAIN -> recipient != null && recipient.contains("@")
                    ? recipient.substring(recipient.indexOf('@') + 1)
                    : null;
            case CONTACT_NAME -> request.getContact() != null
                    ? (request.getContact().getFirstName() + " " + request.getContact().getLastName()).trim()
                    : null;
        };
    }

    private void applyRule(TicketRoutingRule rule, Request request) {
        if (rule.getAssignTeamId() != null && request.getAssignedTeam() == null) {
            teamRepo.findById(rule.getAssignTeamId()).ifPresent(request::setAssignedTeam);
        }
        if (rule.getAssignUserId() != null && request.getAssignedTo() == null) {
            userRepo.findById(rule.getAssignUserId()).ifPresent(request::setAssignedTo);
        }
        if (rule.getSetStatus() != null) {
            request.setStatus(rule.getSetStatus());
        }
        if (rule.getSetPriority() != null) {
            request.setPriority(rule.getSetPriority());
        }
    }

    private boolean matchesRegex(String value, String pattern) {
        try {
            return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(value).find();
        } catch (PatternSyntaxException e) {
            log.warn("Invalid regex in routing rule: {}", pattern);
            return false;
        }
    }
}
