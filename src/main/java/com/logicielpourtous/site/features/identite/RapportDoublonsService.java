package com.logicielpourtous.site.features.identite;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.logicielpourtous.site.features.identite.GroupeDoublon.LigneSource;

/**
 * Rapport des doublons deja presents dans les tables existantes. Lecture seule : aucune fusion ni correction
 * automatique. Les tables des autres modules sont lues en SQL pour que ce module ne depende d'aucun autre.
 *
 * <p>Regles :
 * <ul>
 *   <li>Le SIREN d'une fiche est celui de la colonne {@code siren}, sinon les 9 premiers chiffres du
 *       {@code siret}, sinon (TVA francaise seule) les 9 derniers chiffres de la TVA.</li>
 *   <li>Un groupe {@code SIREN} reunit les fiches (de n'importe quelles tables) qui partagent un SIREN, s'il y
 *       en a au moins 2. Un tenant relie a une fiche client ({@code tenants.customer_id}) du MEME groupe est
 *       la meme societe, pas un doublon : il est retire du groupe.</li>
 *   <li>Un groupe {@code TVA} reunit celles qui partagent une TVA parmi les fiches SANS SIREN (societes
 *       etrangeres). Les fiches sans aucun identifiant sont ignorees.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class RapportDoublonsService {

    /** TVA francaise : FR + cle (2 caracteres) + SIREN (9 chiffres). */
    private static final Pattern TVA_FRANCAISE = Pattern.compile("^FR[0-9A-Z]{2}([0-9]{9})$");

    private record Fiche(LigneSource ligne, String siren, String tva, Long clientLie) {
    }

    private final JdbcTemplate jdbc;

    public RapportDoublonsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<GroupeDoublon> rapport() {
        List<Fiche> fiches = new ArrayList<>();
        fiches.addAll(jdbc.query("SELECT id, companyname, siren, siret, vat_number, tenant_id FROM customers",
                (rs, i) -> {
                    long tenant = rs.getLong(6);
                    String detail = rs.wasNull() ? "" : "tenant " + tenant;
                    return fiche("customers", rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), detail, null);
                }));
        fiches.addAll(jdbc.query("SELECT id, name, siren, vat_number, customer_id FROM tenants",
                (rs, i) -> {
                    long client = rs.getLong(5);
                    Long clientLie = rs.wasNull() ? null : client;
                    return fiche("tenants", rs.getLong(1), rs.getString(2), rs.getString(3), null,
                            rs.getString(4), "", clientLie);
                }));
        fiches.addAll(jdbc.query("SELECT id, company_name, siret, vat_number FROM partner_esns",
                (rs, i) -> fiche("partner_esns", rs.getLong(1), rs.getString(2), null, rs.getString(3),
                        rs.getString(4), "", null)));

        List<GroupeDoublon> groupes = new ArrayList<>();
        groupes.addAll(grouper("SIREN", fiches, Fiche::siren, false));
        groupes.addAll(grouper("TVA", fiches, Fiche::tva, true));
        groupes.sort(Comparator.comparing(GroupeDoublon::nature).thenComparing(GroupeDoublon::cle));
        return groupes;
    }

    private static Fiche fiche(String table, Long id, String nom, String siren, String siret, String tva,
                               String detail, Long clientLie) {
        String sirenNormalise = NumerosSociete.chiffres(siren);
        String siretNormalise = NumerosSociete.chiffres(siret);
        String tvaNormalisee = NumerosSociete.tva(tva);
        if (sirenNormalise == null && siretNormalise != null && siretNormalise.length() >= 9) {
            sirenNormalise = siretNormalise.substring(0, 9);
        }
        if (sirenNormalise == null && tvaNormalisee != null) {
            Matcher m = TVA_FRANCAISE.matcher(tvaNormalisee);
            if (m.matches()) {
                sirenNormalise = m.group(1);
            }
        }
        return new Fiche(new LigneSource(table, id, nom, detail), sirenNormalise, tvaNormalisee, clientLie);
    }

    private static List<GroupeDoublon> grouper(String nature, List<Fiche> fiches, Function<Fiche, String> cle,
                                               boolean sansSirenSeulement) {
        Map<String, List<Fiche>> parCle = new LinkedHashMap<>();
        for (Fiche f : fiches) {
            String k = cle.apply(f);
            if (k == null || (sansSirenSeulement && f.siren() != null)) {
                continue;
            }
            parCle.computeIfAbsent(k, x -> new ArrayList<>()).add(f);
        }
        List<GroupeDoublon> resultat = new ArrayList<>();
        parCle.forEach((k, membres) -> {
            List<LigneSource> lignes = new ArrayList<>();
            for (Fiche f : membres) {
                if (!estTenantDeUneFicheClientDuGroupe(f, membres)) {
                    lignes.add(f.ligne());
                }
            }
            if (lignes.size() >= 2) {
                lignes.sort(Comparator.comparing(LigneSource::table).thenComparing(LigneSource::id));
                resultat.add(new GroupeDoublon(nature, k, lignes));
            }
        });
        return resultat;
    }

    /** Un tenant relie a une fiche client presente dans le meme groupe est la meme societe, pas un doublon. */
    private static boolean estTenantDeUneFicheClientDuGroupe(Fiche f, List<Fiche> membres) {
        if (!"tenants".equals(f.ligne().table()) || f.clientLie() == null) {
            return false;
        }
        return membres.stream().anyMatch(m -> "customers".equals(m.ligne().table())
                && m.ligne().id().equals(f.clientLie()));
    }
}
