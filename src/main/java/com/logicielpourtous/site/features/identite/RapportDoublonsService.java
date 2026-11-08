package com.logicielpourtous.site.features.identite;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.logicielpourtous.site.features.identite.GroupeDoublon.LigneSource;

/**
 * Rapport des doublons deja presents dans les tables existantes. Lecture seule : aucune fusion ni correction
 * automatique. Les tables des autres modules sont lues en SQL pour que ce module ne depende d'aucun autre.
 *
 * <p>Regle : un groupe {@code SIREN} reunit les fiches (de n'importe quelles tables) qui partagent un SIREN
 * (colonne {@code siren}, sinon les 9 premiers chiffres du {@code siret}) ; un groupe {@code TVA} reunit
 * celles qui partagent une TVA parmi les fiches SANS SIREN (societes etrangeres), pour ne pas compter deux
 * fois une societe francaise. Les fiches sans aucun identifiant sont ignorees.
 */
@Service
@Transactional(readOnly = true)
public class RapportDoublonsService {

    private record Fiche(LigneSource ligne, String siren, String tva) {
    }

    private final JdbcTemplate jdbc;

    public RapportDoublonsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<GroupeDoublon> rapport() {
        List<Fiche> fiches = new ArrayList<>();
        fiches.addAll(jdbc.query("SELECT id, companyname, siren, siret, vat_number FROM customers",
                (rs, i) -> fiche("customers", rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5))));
        fiches.addAll(jdbc.query("SELECT id, name, siren, vat_number FROM tenants",
                (rs, i) -> fiche("tenants", rs.getLong(1), rs.getString(2), rs.getString(3), null, rs.getString(4))));
        fiches.addAll(jdbc.query("SELECT id, company_name, siret, vat_number FROM partner_esns",
                (rs, i) -> fiche("partner_esns", rs.getLong(1), rs.getString(2), null, rs.getString(3), rs.getString(4))));

        List<GroupeDoublon> groupes = new ArrayList<>();
        groupes.addAll(grouper("SIREN", fiches, Fiche::siren, false));
        groupes.addAll(grouper("TVA", fiches, Fiche::tva, true));
        groupes.sort(Comparator.comparing(GroupeDoublon::nature).thenComparing(GroupeDoublon::cle));
        return groupes;
    }

    private static Fiche fiche(String table, Long id, String nom, String siren, String siret, String tva) {
        String sirenNormalise = NumerosSociete.chiffres(siren);
        String siretNormalise = NumerosSociete.chiffres(siret);
        if (sirenNormalise == null && siretNormalise != null && siretNormalise.length() >= 9) {
            sirenNormalise = siretNormalise.substring(0, 9);
        }
        return new Fiche(new LigneSource(table, id, nom), sirenNormalise, NumerosSociete.tva(tva));
    }

    private static List<GroupeDoublon> grouper(String nature, List<Fiche> fiches,
                                               java.util.function.Function<Fiche, String> cle, boolean sansSirenSeulement) {
        Map<String, List<LigneSource>> parCle = new LinkedHashMap<>();
        for (Fiche f : fiches) {
            String k = cle.apply(f);
            if (k == null || (sansSirenSeulement && f.siren() != null)) {
                continue;
            }
            parCle.computeIfAbsent(k, x -> new ArrayList<>()).add(f.ligne());
        }
        List<GroupeDoublon> resultat = new ArrayList<>();
        parCle.forEach((k, lignes) -> {
            if (lignes.size() >= 2) {
                lignes.sort(Comparator.comparing(LigneSource::table).thenComparing(LigneSource::id));
                resultat.add(new GroupeDoublon(nature, k, lignes));
            }
        });
        return resultat;
    }
}
