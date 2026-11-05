/**
 * Module {@code licence} : comptes de licence (MP) permettant l'auto-enrolement de serveurs
 * secondaires auprès de ce maitre, ET (Task 5) l'ecran d'administration I-4 des entites
 * {@code SecondaryServer}/{@code SyncConflict} du moteur de synchronisation (module {@code sync}).
 *
 * <p>Dependances externes declarees explicitement, verifiees par {@code ModularityTests}.
 * {@code commun} ({@code ServerRole}/{@code ServerRoleProperties}, pour la garde
 * "disponible seulement si {@code app.role=MASTER}"), {@code customers} (liste deroulante de
 * generation de jeton), {@code sync} ({@code SecondaryServer}/{@code SyncConflict}/{@code
 * RequestSyncApplier}, lecture/ecriture directe depuis l'ecran I-4 — Task 5) et {@code request}
 * ({@code Request}/{@code RequestRepository}, pour resoudre un {@code SyncConflict.entitySyncId}
 * avant d'appliquer son payload — Task 5).
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"commun", "customers", "sync", "request"})
package com.logicielpourtous.site.features.licence;
