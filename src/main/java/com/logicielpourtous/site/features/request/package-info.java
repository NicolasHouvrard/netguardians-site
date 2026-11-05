/**
 * Module {@code request} : demandes/tickets, commentaires, pieces jointes, temps passe.
 *
 * <p>Dependances externes declarees explicitement, verifiees par {@code ModularityTests}.
 */
@ApplicationModule(allowedDependencies = {
        "auth", "commun", "customers", "intervention", "invoice", "mail",
        "order", "role", "subscription", "team", "user"
})
package com.logicielpourtous.site.features.request;

import org.springframework.modulith.ApplicationModule;
