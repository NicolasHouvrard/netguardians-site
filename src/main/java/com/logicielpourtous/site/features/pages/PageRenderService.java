package com.logicielpourtous.site.features.pages;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.customers.Contact;
import com.logicielpourtous.site.features.customers.ContactRepository;
import com.logicielpourtous.site.features.pages.widget.PublicWidget;
import com.logicielpourtous.site.features.pages.widget.WidgetRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class PageRenderService {

    private static final Logger log = LoggerFactory.getLogger(PageRenderService.class);

    private final ObjectMapper objectMapper;
    private final WidgetRegistry widgetRegistry;
    private final SpringTemplateEngine templateEngine;
    private final ContactRepository contactRepository;

    public PageRenderService(ObjectMapper objectMapper,
                             WidgetRegistry widgetRegistry,
                             SpringTemplateEngine templateEngine,
                             ContactRepository contactRepository) {
        this.objectMapper = objectMapper;
        this.widgetRegistry = widgetRegistry;
        this.templateEngine = templateEngine;
        this.contactRepository = contactRepository;
    }

    public List<RenderedBlock> renderBlocks(List<PageBlock> blocks) {
        List<RenderedBlock> result = new ArrayList<>();
        for (PageBlock block : blocks) {
            Map<String, Object> contentMap = parseContent(block.getContent());
            String widgetHtml = null;
            if (block.getBlockType() == BlockType.WIDGET) {
                String widgetId = String.valueOf(contentMap.getOrDefault("widgetId", ""));
                widgetHtml = renderWidget(widgetId, Collections.emptyMap());
            }
            result.add(new RenderedBlock(block, contentMap, widgetHtml));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseContent(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse block content JSON: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    public String renderWidget(String widgetId, Map<String, String> params) {
        PublicWidget widget = widgetRegistry.get(widgetId);
        if (widget == null) {
            return "<div class=\"alert alert-warning\"><i class=\"fas fa-exclamation-triangle me-2\"></i>"
                    + "Widget introuvable : <code>" + widgetId + "</code></div>";
        }
        try {
            Context ctx = new Context(Locale.FRENCH);
            Map<String, Object> data = widget.getData(params);
            data.forEach(ctx::setVariable);

            // Injecter l'utilisateur connecté si disponible
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)
                    && auth.getPrincipal() instanceof CustomUserDetails loggedUser) {
                ctx.setVariable("loggedUser", loggedUser);
                contactRepository.findFirstByEmail(loggedUser.getUsername())
                        .ifPresent(c -> ctx.setVariable("contact", c));
            }

            return templateEngine.process("widgets/" + widgetId, ctx);
        } catch (Exception e) {
            log.error("Error rendering widget {}: {}", widgetId, e.getMessage(), e);
            return "<div class=\"alert alert-danger\">Erreur lors du rendu du widget : " + widgetId + "</div>";
        }
    }
}
