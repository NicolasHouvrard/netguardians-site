package com.logicielpourtous.site.features.auth;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.logicielpourtous.site.features.identite.EnregistrementConcurrentException;
import com.logicielpourtous.site.features.identite.GroupeDoublon;
import com.logicielpourtous.site.features.identite.IdentiteInvalideException;
import com.logicielpourtous.site.features.identite.IdentiteSociete;
import com.logicielpourtous.site.features.identite.RapportDoublonsService;
import com.logicielpourtous.site.features.identite.RegistreSocietes;
import com.logicielpourtous.site.features.identite.SocieteDejaConnueException;
import com.logicielpourtous.site.features.identite.SocieteIdentite;
import com.logicielpourtous.site.features.identite.TypeSociete;

/**
 * Reprise des societes deja presentes ({@code customers}, {@code partner_esns}) dans le registre unique, pour
 * qu'une inscription ne puisse pas recreer une societe qui existe deja. Operation du Maitre, jamais destructive :
 * aucune fiche n'est modifiee et aucune societe n'est fusionnee.
 *
 * <p><b>Regles.</b>
 * <ul>
 *   <li>Proprietaire d'un client = le partenaire nomme par {@code customers.tenant_id} ({@code NULL} = le Maitre).
 *       Un {@code partner_esns} n'a pas de tenant (la table n'a aucune colonne de rattachement) : proprietaire
 *       {@code null}.</li>
 *   <li>Type : {@code CLIENT} pour un client, {@code PARTENAIRE} pour une ESN, {@code FOURNISSEUR} pour toute
 *       societe a TVA etrangere (le registre n'accepte pas d'autre type pour elles).</li>
 *   <li>Sont ignorees et LISTEES : les fiches de numeros invalides ou incomplets (la TVA est obligatoire, elle
 *       n'est jamais deduite du SIREN), les fiches d'un groupe du rapport de doublons, et celles dont l'identite
 *       est deja detenue par une AUTRE fiche du registre.</li>
 *   <li>Idempotent : une fiche deja liee au registre ({@code fiche_type} + {@code fiche_id}) est sautee en silence.</li>
 * </ul>
 *
 * <p><b>Transactions.</b> Pas de {@code @Transactional} sur la boucle : chaque fiche s'ecrit dans sa propre
 * transaction ({@code REQUIRES_NEW}). Un conflit d'unicite avorte la transaction PostgreSQL de cette seule fiche ;
 * les autres, deja validees ou a venir, ne sont pas touchees.
 */
@Service
public class RepriseRegistreService {

    static final String FICHE_CUSTOMERS = "customers";
    static final String FICHE_PARTNER_ESNS = "partner_esns";

    /** Bilan : {@code details} liste chaque fiche ignoree, avec la raison. */
    public record BilanReprise(int reprises, int ignoreesDoublon, int ignoreesInvalides, List<String> details) {
    }

    private record Fiche(String table, long id, String nom, String siren, String siret, String tva,
                         Long proprietaireTenantId) {
        String libelle() {
            return table + " #" + id + " (" + nom + ")";
        }

        String cle() {
            return table + ":" + id;
        }
    }

    private final JdbcTemplate jdbc;
    private final RegistreSocietes registre;
    private final RapportDoublonsService rapportDoublons;
    private final TransactionTemplate nouvelleTransaction;

    public RepriseRegistreService(JdbcTemplate jdbc, RegistreSocietes registre,
                                  RapportDoublonsService rapportDoublons,
                                  PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.registre = registre;
        this.rapportDoublons = rapportDoublons;
        this.nouvelleTransaction = new TransactionTemplate(transactionManager);
        this.nouvelleTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** @param simulation {@code true} : calcule le bilan sans rien ecrire */
    public BilanReprise reprendre(boolean simulation) {
        Set<String> enDoublon = fichesEnDoublon();
        Set<String> dejaLiees = fichesDejaLiees();

        int reprises = 0;
        int doublons = 0;
        int invalides = 0;
        List<String> details = new ArrayList<>();

        for (Fiche fiche : lireFiches()) {
            if (dejaLiees.contains(fiche.cle())) {
                continue;
            }
            if (enDoublon.contains(fiche.cle())) {
                doublons++;
                details.add(fiche.libelle() + " : ignorée, société présente en plusieurs fiches (voir le rapport des doublons).");
                continue;
            }
            IdentiteSociete identite;
            try {
                identite = IdentiteSociete.depuis(fiche.siren(), fiche.siret(), fiche.tva());
            } catch (IdentiteInvalideException e) {
                invalides++;
                details.add(fiche.libelle() + " : ignorée, numéros invalides ou incomplets (" + e.getMessage() + ")");
                continue;
            }
            TypeSociete type = typePour(fiche, identite);
            if (simulation) {
                if (registre.trouver(identite).isPresent()) {
                    doublons++;
                    details.add(fiche.libelle() + " : ignorée, cette société est déjà au registre par une autre fiche.");
                } else {
                    reprises++;
                }
                continue;
            }
            try {
                nouvelleTransaction.executeWithoutResult(statut -> {
                    SocieteIdentite ligne = registre.enregistrer(type, identite, fiche.proprietaireTenantId());
                    // Entite geree par la transaction de ce modele : le lien est ecrit au commit.
                    ligne.setFicheType(fiche.table());
                    ligne.setFicheId(fiche.id());
                });
                reprises++;
            } catch (SocieteDejaConnueException e) {
                doublons++;
                details.add(fiche.libelle() + " : ignorée, cette société est déjà au registre par une autre fiche.");
            } catch (EnregistrementConcurrentException e) {
                doublons++;
                details.add(fiche.libelle() + " : ignorée, conflit d'enregistrement simultané ; relancez la reprise.");
            }
        }
        return new BilanReprise(reprises, doublons, invalides, List.copyOf(details));
    }

    private static TypeSociete typePour(Fiche fiche, IdentiteSociete identite) {
        if (identite.etrangere()) {
            return TypeSociete.FOURNISSEUR;
        }
        return FICHE_PARTNER_ESNS.equals(fiche.table()) ? TypeSociete.PARTENAIRE : TypeSociete.CLIENT;
    }

    private Set<String> fichesEnDoublon() {
        Set<String> cles = new HashSet<>();
        for (GroupeDoublon g : rapportDoublons.rapport()) {
            g.lignes().forEach(l -> cles.add(l.table() + ":" + l.id()));
        }
        return cles;
    }

    private Set<String> fichesDejaLiees() {
        return new HashSet<>(jdbc.query(
                "SELECT fiche_type, fiche_id FROM societes_identites WHERE fiche_type IS NOT NULL AND fiche_id IS NOT NULL",
                (rs, i) -> rs.getString(1) + ":" + rs.getLong(2)));
    }

    private List<Fiche> lireFiches() {
        List<Fiche> fiches = new ArrayList<>();
        fiches.addAll(jdbc.query(
                "SELECT id, companyname, siren, siret, vat_number, tenant_id FROM customers ORDER BY id",
                (rs, i) -> {
                    long tenant = rs.getLong(6);
                    Long proprietaire = rs.wasNull() ? null : tenant;
                    return new Fiche(FICHE_CUSTOMERS, rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), proprietaire);
                }));
        fiches.addAll(jdbc.query(
                "SELECT id, company_name, siret, vat_number FROM partner_esns ORDER BY id",
                (rs, i) -> new Fiche(FICHE_PARTNER_ESNS, rs.getLong(1), rs.getString(2), null,
                        rs.getString(3), rs.getString(4), null)));
        return fiches;
    }
}
