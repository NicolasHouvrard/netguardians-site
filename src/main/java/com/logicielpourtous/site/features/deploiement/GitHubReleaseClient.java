package com.logicielpourtous.site.features.deploiement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Client HTTP sortant vers l'API publique GitHub (aucune authentification requise pour un depot
 * dont les releases sont publiques — l'image Docker l'est deja, voir le plan de ce chantier),
 * utilise par {@link DeploiementScheduler} pour detecter une nouvelle version a deployer.
 *
 * <p>Bean {@code @Component} INCONDITIONNEL (pas de {@code @ConditionalOnProperty}) : contrairement
 * a {@code LicenceClient} (actif seulement si {@code app.licence.master-url} est configure),
 * {@link DeploiementScheduler} qui depend de cette classe doit lui-meme exister sur TOUT noeud (son
 * garde-fou de role est a l'EXECUTION, voir sa javadoc) — un bean absent empecherait le contexte
 * Spring de demarrer sur un noeud MASTER pas encore configure. Memes discipline et message
 * d'erreur (logue UNE SEULE fois, jamais d'exception) que {@code AgentCleApiFileWriter} pour sa
 * propre propriete obligatoire.
 *
 * <p><b>{@code app.deploiement.github-repo} OBLIGATOIRE, AUCUNE valeur par defaut devinee</b> : ce
 * depot n'etant configure nulle part ailleurs dans ce projet (ni {@code pom.xml}, ni aucune URL
 * {@code github.com} existante), deviner un proprietaire/depot par defaut risquerait de sonder le
 * depot de quelqu'un d'autre silencieusement — voir {@code application.properties.example}.
 *
 * <p><b>{@code GET /releases/latest}, PAS {@code GET /tags}</b> (revue finale whole-branch,
 * finding 1) : un tag {@code v*} pousse est visible sur {@code /tags} DES LA RECEPTION du push,
 * mais la CI met typiquement plusieurs minutes a construire/tester/publier l'image Docker
 * correspondante sur GHCR — le Maitre (sondage toutes les 5 minutes par defaut) pouvait donc
 * detecter le tag avant que l'image n'existe, et comme {@code DeploiementController} marque l'ordre
 * consomme dès le premier service, un tel ordre premature n'etait JAMAIS retente avant le PROCHAIN
 * tag : la release ne se deployait silencieusement nulle part. {@code /releases/latest} ne renvoie
 * une release qu'une fois PUBLIEE (dans un pipeline CI typique, APRES le succes du build/test/push
 * de l'image, pas a l'instant du push du tag), et a une semantique "la plus recente" unique et
 * documentee par GitHub (derniere release non-prerelease/non-draft) — resout AUSSI
 * l'ambiguite de l'ordre de {@code /tags} (non documente ; {@code v1.9} vs {@code v1.10} en tri
 * texte, ou un tag non-{@code v*} ramasse par erreur), plus completement qu'un filtrage/tri manuel
 * de la liste brute des tags.
 */
@Component
public class GitHubReleaseClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubReleaseClient.class);

    private static final String API_BASE_URL = "https://api.github.com";

    private final RestClient restClient;
    private final String repo;

    private boolean erreurConfigurationDejaLoguee;

    public GitHubReleaseClient(RestClient.Builder restClientBuilder,
                               @Value("${app.deploiement.github-repo:}") String repo) {
        this.restClient = restClientBuilder.baseUrl(API_BASE_URL).build();
        this.repo = repo;
    }

    /** Reponse de {@code GET /repos/{owner}/{repo}/releases/latest} — seul le tag associe (ex.
     * {@code "v1.2.3"}) nous interesse ici, les autres champs de la reponse GitHub sont ignores. */
    public record ReleaseDto(@JsonProperty("tag_name") String tagName) {}

    /**
     * Renvoie le tag de la derniere release PUBLIEE du depot configure ({@code GET
     * /releases/latest}, voir la javadoc de classe), ou {@code null} si la propriete n'est pas
     * configuree, si le depot n'a encore AUCUNE release publiee (404, traite comme un etat normal —
     * pas une erreur, aucun log), ou si l'appel GitHub echoue pour une autre raison (reseau,
     * rate-limit, etc., alors loggue en WARN) — jamais d'exception : {@link DeploiementScheduler}
     * retente simplement au prochain cycle.
     */
    public String dernierTag() {
        if (repo == null || repo.isBlank()) {
            if (!erreurConfigurationDejaLoguee) {
                log.error("Deploiement : app.deploiement.github-repo n'est pas configure (ou vide) — "
                        + "impossible de detecter une nouvelle version. Configurez le depot GitHub "
                        + "(ex. \"mon-org/site\"), voir application.properties.example.");
                erreurConfigurationDejaLoguee = true;
            }
            return null;
        }
        try {
            ReleaseDto release = restClient.get()
                    .uri("/repos/" + repo + "/releases/latest")
                    .retrieve()
                    .body(ReleaseDto.class);
            return release == null ? null : release.tagName();
        } catch (HttpClientErrorException.NotFound e) {
            // Aucune release publiee encore pour ce depot : etat normal (ex. tout premier
            // deploiement de ce chantier), pas une erreur — meme traitement silencieux qu'une
            // liste de tags vide avant ce correctif.
            return null;
        } catch (RestClientException e) {
            log.warn("Deploiement : interrogation de GitHub (releases/latest de {}) en echec, "
                    + "nouvel essai au prochain cycle : {}", repo, e.toString());
            return null;
        }
    }
}
