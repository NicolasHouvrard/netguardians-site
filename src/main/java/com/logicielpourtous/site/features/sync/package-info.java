/**
 * Module {@code sync} : moteur de synchronisation multi-serveurs — entites locales
 * (serveurs secondaires enroles, outbox, conflits), evenements de domaine, transport
 * REST et planification (Tasks 2-4 de ce plan).
 *
 * <p>Dependances externes declarees explicitement, verifiees par {@code ModularityTests}.
 * {@code commun} ({@code ServerRole}) et {@code request} (evenements de domaine
 * {@code RequestChangedEvent}/{@code CommentAddedEvent}, {@code Request}/{@code Comment},
 * {@code RequestRepository} — Task 2).
 */
@ApplicationModule(allowedDependencies = { "commun", "request" })
package com.logicielpourtous.site.features.sync;

import org.springframework.modulith.ApplicationModule;
