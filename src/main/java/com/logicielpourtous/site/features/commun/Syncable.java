package com.logicielpourtous.site.features.commun;

import java.util.UUID;

import org.springframework.data.domain.AbstractAggregateRoot;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Version;

/**
 * Classe de base JPA fournissant une clé de synchronisation ({@code syncId})
 * stable, generee automatiquement a la creation si absente et jamais
 * modifiee par la suite.
 *
 * <p>Etend {@link AbstractAggregateRoot} : une sous-classe peut enregistrer un evenement
 * de domaine via {@code registerEvent(...)} ; Spring Data le publie automatiquement au
 * prochain {@code repository.save(entite)} (puis purge la liste), quel que soit l'appelant.
 * L'enregistrement doit se faire AVANT l'appel a {@code save()}, jamais depuis un callback
 * JPA ({@code @PrePersist}/{@code @PreUpdate}) : Spring Data collecte les evenements de
 * l'argument passe a {@code save()}, et un callback JPA declenche au flush peut s'executer
 * apres cette collecte (evenement alors perdu ou publie au save() suivant).
 *
 * <p>Le champ {@code domainEvents} d'{@link AbstractAggregateRoot} n'est pas mappe par JPA
 * ({@link AbstractAggregateRoot} n'est pas un {@code @MappedSuperclass}).
 */
@MappedSuperclass
public class Syncable extends AbstractAggregateRoot<Syncable> {

    @Column(name = "sync_id", updatable = false)
    private UUID syncId;

    /**
     * Version JPA (verrouillage optimiste) : incrementee par Hibernate a chaque UPDATE, base de la
     * detection de conflit de synchronisation. Une ecriture partant d'une version perimee echoue
     * ({@code ObjectOptimisticLockingFailureException}).
     *
     * <p><b>{@code Long} et non {@code long}, choix verifie empiriquement (rapport Task 3b)</b> :
     * <ul>
     * <li>{@code null} = entite jamais persistee : Spring Data ({@code isNew()}) s'appuie dessus pour
     * choisir {@code persist} vs {@code merge}. Une instance liee depuis un formulaire avec un
     * {@code id} mais SANS version echoue donc bruyamment au lieu d'ecraser en silence.</li>
     * <li>Avec {@code long}, un formulaire oubliant la version envoie {@code 0}, qui correspond a la
     * version de toute ligne jamais modifiee : la 1re edition passe, l'oubli reste invisible.</li>
     * </ul>
     *
     * <p>Les lignes existantes avant l'ajout de cette colonne (version {@code NULL} en base) cassent
     * TOUTES les ecritures (NPE au flush, ou {@code PropertyValueException} au merge) : elles sont
     * backfillees a {@code 0} par {@code sync-id-backfill-migration.sql} (spring.sql.init).
     *
     * <p>Tout formulaire lie directement a une entite {@code Syncable} ({@code @ModelAttribute}) DOIT
     * round-tripper ce champ ({@code <input type="hidden" th:field="*{version}">}) : d'ou le setter
     * public, utilise par la liaison Spring MVC. Ne pas l'appeler ailleurs.
     */
    @Version
    private Long version;

    public UUID getSyncId() {
        return syncId;
    }

    public Long getVersion() {
        return version;
    }

    /** Reserve a la liaison de formulaire Spring MVC (voir {@link #version}). */
    public void setVersion(Long version) {
        this.version = version;
    }

    /**
     * Reserve au module {@code sync} : creation de la copie locale d'une entite RECUE d'un autre
     * serveur, qui doit garder le {@code syncId} d'origine (cle de correspondance entre serveurs).
     * Uniquement sur une instance neuve, jamais persistee : le {@code syncId} d'une entite existante
     * reste immuable.
     *
     * @throws IllegalStateException si l'instance a deja un {@code syncId} ou une version
     */
    public void adopterSyncIdRecu(UUID syncIdRecu) {
        if (syncId != null || version != null) {
            throw new IllegalStateException("syncId deja attribue ou entite deja persistee : syncId immuable");
        }
        this.syncId = java.util.Objects.requireNonNull(syncIdRecu, "syncIdRecu");
    }

    @PrePersist
    protected void assignSyncId() {
        if (syncId == null) {
            syncId = UUID.randomUUID();
        }
    }
}
