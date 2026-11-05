package com.logicielpourtous.site.features.intra.widget;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import org.springframework.ui.Model;

/**
 * Interface à implémenter pour créer un widget.
 * 1 implémentation @Component + 1 template Thymeleaf = 1 widget.
 */
public interface IntraWidgetProvider {

    /** Clé unique du widget (ex : "OPEN_TICKETS"). */
    String key();

    /** Remplit le modèle pour le rendu du fragment. */
    void populate(Model model, CustomUserDetails user);

    /** Chemin du template (fragment "widget" attendu). Défaut : _kpi partagé. */
    default String template() {
        return "intra/widgets/_kpi";
    }
}
