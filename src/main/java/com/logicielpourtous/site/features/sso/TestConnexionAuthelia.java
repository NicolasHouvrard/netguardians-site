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
 * Garde-fous : https seulement (port 443 uniquement), ni identifiants integres ni fragment, delais de 3 s, AUCUNE redirection suivie, taille de
 * reponse bornee, hotes refuses : adresses IP litterales, noms locaux ({@code localhost}, sans point, {@code .local},
 * {@code .internal}, {@code .lan}...) et noms dont UNE des adresses resolues n'est pas une adresse
 * unicast globale (loopback, privee, link-local, 100.64/10, documentation, multicast, NAT64, 6to4, Teredo, IPv4
 * mappee/compatible : l'IPv4 contenue est controlee). La resolution DNS est bornee par le delai global.
 * <p>
 * La route est reservee aux administrateurs et ne renvoie jamais le corps de la reponse, seulement une categorie.
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
    private final Duration delai;

    @Autowired
    public TestConnexionAuthelia() {
        this(new TransportHttp(DELAI), InetAddress::getAllByName);
    }

    TestConnexionAuthelia(Transport transport, Resolveur resolveur) {
        this(transport, resolveur, DELAI);
    }

    TestConnexionAuthelia(Transport transport, Resolveur resolveur, Duration delai) {
        this.transport = transport;
        this.resolveur = resolveur;
        this.delai = delai;
    }

    public Resultat tester(String emetteur) {
        URI cible = cibleOuNull(emetteur);
        if (cible == null) {
            return Resultat.ADRESSE_REFUSEE;
        }
        try {
            InetAddress[] adresses = resoudreBorne(cible.getHost());
            if (adresses == null || adresses.length == 0) {
                return Resultat.INJOIGNABLE;
            }
            for (InetAddress a : adresses) {
                if (adresseInterdite(a)) {
                    return Resultat.ADRESSE_REFUSEE;
                }
            }
        } catch (UnknownHostException | TimeoutException e) {
            return Resultat.INJOIGNABLE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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

    /** Resolution DNS bornee par le delai global (la resolution du systeme n'a pas de delai propre). */
    private InetAddress[] resoudreBorne(String hote) throws UnknownHostException, TimeoutException, InterruptedException {
        CompletableFuture<InetAddress[]> futur = CompletableFuture.supplyAsync(() -> {
            try {
                return resolveur.resoudre(hote);
            } catch (UnknownHostException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        });
        try {
            return futur.get(delai.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof UnknownHostException u) {
                throw u;
            }
            if (c != null && c.getCause() instanceof UnknownHostException u) {
                throw u;
            }
            throw new UnknownHostException(hote);
        } catch (TimeoutException e) {
            futur.cancel(true);
            throw e;
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
                    || (u.getPort() != -1 && u.getPort() != 443) || u.getRawUserInfo() != null || u.getRawFragment() != null || u.getRawQuery() != null
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

    /** Refuse tout ce qui n'est pas une adresse unicast globale (IPv4 publique ou IPv6 dans 2000::/3 hors reserves). */
    static boolean adresseInterdite(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (b.length == 4) {
            return ipv4Interdite(b);
        }
        if (b.length != 16) {
            return true;
        }
        // IPv4 mappee (::ffff:a.b.c.d) laissee sous forme IPv6 : on controle l'IPv4 contenue.
        boolean mappee = true;
        for (int i = 0; i < 10; i++) {
            mappee &= b[i] == 0;
        }
        if (mappee && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
            return ipv4Interdite(new byte[] { b[12], b[13], b[14], b[15] });
        }
        int o0 = b[0] & 0xff;
        int o1 = b[1] & 0xff;
        if ((o0 & 0xe0) != 0x20) {
            return true; // hors 2000::/3 : ::/96 (IPv4-compatible), 64:ff9b::/96 (NAT64), fc00::/7, fe80::/10, ff00::/8...
        }
        int o2 = b[2] & 0xff;
        int o3 = b[3] & 0xff;
        if (o0 == 0x20 && o1 == 0x01) {
            // 2001::/23 (protocoles IETF dont Teredo 2001::/32), 2001:db8::/32 (documentation)
            return o2 <= 0x01 || (o2 == 0x0d && o3 == 0xb8);
        }
        if (o0 == 0x20 && o1 == 0x02) {
            return true; // 6to4 2002::/16 (embarque une IPv4)
        }
        return o0 == 0x3f && o1 == 0xff && (o2 & 0xf0) == 0x00; // 3fff::/20 (documentation)
    }

    private static boolean ipv4Interdite(byte[] b) {
        int o1 = b[0] & 0xff;
        int o2 = b[1] & 0xff;
        int o3 = b[2] & 0xff;
        return o1 == 0 || o1 == 10 || o1 == 127 || o1 >= 224 || (o1 == 100 && o2 >= 64 && o2 <= 127)
                || (o1 == 169 && o2 == 254) || (o1 == 172 && o2 >= 16 && o2 <= 31) || (o1 == 192 && o2 == 168)
                || (o1 == 192 && o2 == 0 && (o3 == 0 || o3 == 2)) || (o1 == 192 && o2 == 88 && o3 == 99)
                || (o1 == 198 && (o2 == 18 || o2 == 19)) || (o1 == 198 && o2 == 51 && o3 == 100)
                || (o1 == 203 && o2 == 0 && o3 == 113);
    }
}
