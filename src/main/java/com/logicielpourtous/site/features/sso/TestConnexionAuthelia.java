package com.logicielpourtous.site.features.sso;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * « Tester la connexion a Authelia » : interroge {@code <emetteur>/.well-known/openid-configuration} et ne renvoie
 * qu'une categorie (joignable / injoignable / reponse inattendue / adresse refusee), JAMAIS le corps de la reponse.
 * <p>
 * Garde-fous : https seulement, ni identifiants integres ni fragment, delais de 3 s, AUCUNE redirection suivie, taille de
 * reponse bornee, hotes refuses : adresses IP litterales, noms locaux ({@code localhost}, sans point, {@code .local},
 * {@code .internal}, {@code .lan}...) et noms dont UNE des adresses resolues est locale/privee/reservee.
 * <p>
 * Limite connue : la resolution du nom est refaite par le client HTTP au moment de la connexion ; un DNS malveillant
 * qui change de reponse entre les deux (« rebinding ») n'est pas couvert. La route est reservee aux administrateurs et
 * ne renvoie jamais le corps, ce qui limite l'usage a un sondage de joignabilite.
 */
@Component
public class TestConnexionAuthelia {

    static final Duration DELAI = Duration.ofSeconds(3);
    private static final int TAILLE_MAX = 64 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    public enum Resultat { JOIGNABLE, INJOIGNABLE, REPONSE_INATTENDUE, ADRESSE_REFUSEE }

    /** Statut et corps (borne) ; le corps ne sort jamais de cette classe. */
    public record Reponse(int statut, String corps) {
        @Override
        public String toString() {
            return "Reponse[statut=" + statut + "]";
        }
    }

    @FunctionalInterface
    interface Transport {
        Reponse get(URI uri) throws IOException, InterruptedException;
    }

    @FunctionalInterface
    interface Resolveur {
        InetAddress[] resoudre(String hote) throws UnknownHostException;
    }

    /** Transport reel : delai total borne, redirections jamais suivies, corps borne. */
    static final class TransportHttp implements Transport {

        private final Duration delai;
        private final HttpClient client;

        TransportHttp(Duration delai) {
            this.delai = delai;
            this.client = HttpClient.newBuilder().connectTimeout(delai).followRedirects(HttpClient.Redirect.NEVER).build();
        }

        @Override
        public Reponse get(URI uri) throws IOException, InterruptedException {
            HttpRequest requete = HttpRequest.newBuilder(uri).timeout(delai).header("Accept", "application/json").GET()
                    .build();
            AtomicReference<InputStream> flux = new AtomicReference<>();
            CompletableFuture<Reponse> futur = client.sendAsync(requete, HttpResponse.BodyHandlers.ofInputStream())
                    .thenApply(r -> {
                        flux.set(r.body());
                        try (InputStream in = r.body()) {
                            return new Reponse(r.statusCode(), new String(in.readNBytes(TAILLE_MAX), StandardCharsets.UTF_8));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
            try {
                return futur.get(delai.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                fermer(flux.get());
                futur.cancel(true);
                throw new IOException("delai depasse");
            } catch (ExecutionException e) {
                throw new IOException("echec de la requete");
            }
        }

        private static void fermer(InputStream in) {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignore) {
                    // fermeture au mieux
                }
            }
        }
    }

    private final Transport transport;
    private final Resolveur resolveur;

    @Autowired
    public TestConnexionAuthelia() {
        this(new TransportHttp(DELAI), InetAddress::getAllByName);
    }

    TestConnexionAuthelia(Transport transport, Resolveur resolveur) {
        this.transport = transport;
        this.resolveur = resolveur;
    }

    public Resultat tester(String emetteur) {
        URI cible = cibleOuNull(emetteur);
        if (cible == null) {
            return Resultat.ADRESSE_REFUSEE;
        }
        try {
            InetAddress[] adresses = resolveur.resoudre(cible.getHost());
            if (adresses == null || adresses.length == 0) {
                return Resultat.INJOIGNABLE;
            }
            for (InetAddress a : adresses) {
                if (adresseInterdite(a)) {
                    return Resultat.ADRESSE_REFUSEE;
                }
            }
        } catch (UnknownHostException e) {
            return Resultat.INJOIGNABLE;
        }
        try {
            Reponse r = transport.get(cible);
            return r.statut() == 200 && estConfigurationOidc(r.corps()) ? Resultat.JOIGNABLE
                    : Resultat.REPONSE_INATTENDUE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Resultat.INJOIGNABLE;
        } catch (IOException | RuntimeException e) {
            return Resultat.INJOIGNABLE;
        }
    }

    private static boolean estConfigurationOidc(String corps) {
        try {
            JsonNode n = JSON.readTree(corps);
            return n != null && n.isObject() && n.path("issuer").isTextual();
        } catch (IOException e) {
            return false;
        }
    }

    /** Adresse de la configuration OIDC, ou null si l'emetteur est refuse avant tout appel reseau. */
    static URI cibleOuNull(String emetteur) {
        if (emetteur == null || emetteur.isBlank()) {
            return null;
        }
        try {
            URI u = new URI(emetteur.trim());
            String hote = u.getHost();
            if (!"https".equalsIgnoreCase(u.getScheme()) || hote == null || hote.isBlank()
                    || u.getRawUserInfo() != null || u.getRawFragment() != null || u.getRawQuery() != null
                    || hoteInterdit(hote)) {
                return null;
            }
            String chemin = u.getRawPath() == null ? "" : u.getRawPath();
            while (chemin.endsWith("/")) {
                chemin = chemin.substring(0, chemin.length() - 1);
            }
            String port = u.getPort() < 0 ? "" : ":" + u.getPort();
            return new URI("https://" + hote.toLowerCase(Locale.ROOT) + port + chemin
                    + "/.well-known/openid-configuration");
        } catch (URISyntaxException | IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean hoteInterdit(String hote) {
        String h = hote.toLowerCase(Locale.ROOT);
        if (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        if (h.contains(":") || h.startsWith("[") || h.matches("[0-9.]+")) {
            return true; // adresse IP litterale
        }
        return !h.contains(".") || h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local")
                || h.endsWith(".internal") || h.endsWith(".lan") || h.endsWith(".home") || h.endsWith(".intranet")
                || h.endsWith(".corp") || h.endsWith(".private");
    }

    private static boolean adresseInterdite(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int o1 = b[0] & 0xff;
            int o2 = b[1] & 0xff;
            return o1 == 0 || (o1 == 100 && o2 >= 64 && o2 <= 127) || o1 >= 240 || (o1 == 192 && o2 == 0 && (b[2] & 0xff) == 0)
                    || (o1 == 198 && (o2 == 18 || o2 == 19));
        }
        return (b[0] & 0xfe) == 0xfc; // IPv6 unique-local fc00::/7
    }
}
