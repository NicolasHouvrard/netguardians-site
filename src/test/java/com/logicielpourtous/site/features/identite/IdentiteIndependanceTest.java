package com.logicielpourtous.site.features.identite;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Le module {@code identite} ne depend d'AUCUN autre module (contrainte du plan). {@code allowedDependencies = {}}
 * seul ne l'impose pas (Modulith traite un tableau vide comme "tout autoriser") : on verifie donc directement
 * les dependances reelles.
 */
class IdentiteIndependanceTest {

    @Test
    void identiteNeDependDAucunAutreModule() {
        ApplicationModules modules = ApplicationModules.of("com.logicielpourtous.site.features");
        ApplicationModule identite = modules.getModuleByName("identite")
                .orElseThrow(() -> new AssertionError("Module introuvable : identite"));

        List<String> dependances = identite.getDirectDependencies(modules).uniqueModules()
                .map(ApplicationModule::getName).toList();

        assertThat(dependances).as("dependances du module identite").isEmpty();
    }
}
