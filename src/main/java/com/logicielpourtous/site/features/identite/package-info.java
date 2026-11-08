/**
 * Module {@code identite} : identite des societes (SIREN, SIRET, TVA), registre unique et rapport de
 * doublons. Module independant : il ne depend d'aucun autre module (le rapport lit les tables des autres
 * modules en SQL), verifie par {@code ModularityTests}.
 */
@ApplicationModule(allowedDependencies = {})
package com.logicielpourtous.site.features.identite;

import org.springframework.modulith.ApplicationModule;
