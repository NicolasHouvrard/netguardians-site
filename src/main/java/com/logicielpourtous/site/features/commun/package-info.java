/**
 * Module {@code commun} : multi-tenant (schema par tenant), chiffrement, utilitaires partages.
 *
 * <p>Dependances externes declarees explicitement, verifiees par {@code ModularityTests}.
 */
@ApplicationModule(allowedDependencies = { "auth", "customers", "user" })
package com.logicielpourtous.site.features.commun;

import org.springframework.modulith.ApplicationModule;
