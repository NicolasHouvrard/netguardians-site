/**
 * Module {@code plateforme} : plateforme de plugins (chargement, mise à jour en maintenance, routage
 * {@code /api/plugins/**}, administration {@code /api/plateforme/**}, mesure des fuites, dépôt du Maître
 * {@code /api/plateforme/depot}, distribution aux serveurs {@code /api/licence/plugins/**} et état qu'ils rapportent
 * {@code /api/plateforme/serveurs-etat}). Tous ses beans n'existent que si {@code app.plateforme.actif=true} ; ceux du dépôt
 * et de la distribution exigent en plus {@code app.role=MASTER} ({@code ServerRoleProperties}, module {@code commun}).
 *
 * <p>Tireur ({@code TireurPlugins}) : sur le Maître depuis son dépôt ({@code SourceDepotLocal}), ailleurs depuis le
 * Maître par HTTP ({@code SourceMaitreHttp}).
 *
 * <p>Dépendance externe déclarée explicitement, vérifiée par {@code ModularityTests} : {@code commun}
 * ({@code TenantContext}, vidé en fin de requête de plugin par {@code FiltreEnVol} ; {@code ServerRoleProperties} ;
 * {@code CleMaitre}, clé API de licence pour le tirage). {@code CleMaitre} est une interface de {@code commun}
 * implémentée par le module {@code licence} : {@code plateforme} ne dépend donc pas de {@code licence}, et
 * {@code licence} ne dépend jamais de {@code plateforme} (aucun cycle). Le SDK partagé
 * ({@code com.logicielpourtous.site.sdk}) est hors de la racine {@code features} analysée par Modulith.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"commun"})
package com.logicielpourtous.site.features.plateforme;
