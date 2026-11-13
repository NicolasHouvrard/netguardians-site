/**
 * Module {@code plateforme} : plateforme de plugins (chargement, mise à jour en maintenance, routage
 * {@code /api/plugins/**}, administration {@code /api/plateforme/**}, mesure des fuites). Tous ses beans n'existent
 * que si {@code app.plateforme.actif=true}.
 *
 * <p>Dépendance externe déclarée explicitement, vérifiée par {@code ModularityTests} : {@code commun}
 * ({@code TenantContext}, vidé en fin de requête de plugin par {@code FiltreEnVol}). Le SDK partagé
 * ({@code com.logicielpourtous.site.sdk}) est hors de la racine {@code features} analysée par Modulith.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"commun"})
package com.logicielpourtous.site.features.plateforme;
