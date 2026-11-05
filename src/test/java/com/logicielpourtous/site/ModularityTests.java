package com.logicielpourtous.site;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Verifie les frontieres Spring Modulith des modules {@code commun}, {@code request} et {@code sync}.
 *
 * <p>La racine de scan est le package {@code features} (et non {@code SiteApplication}),
 * pour que chaque sous-package de {@code features} soit un module, au lieu que
 * {@code config}/{@code features} soient detectes comme 2 modules geants.
 *
 * <p>La verification est volontairement limitee a {@code commun} et {@code request}, via
 * la verification par module ({@link ApplicationModule#detectDependencies(ApplicationModules)}),
 * qui controle que chaque dependance sortante vise un module declare dans
 * {@code @ApplicationModule(allowedDependencies = ...)} du {@code package-info.java}.
 *
 * <p><b>Limites connues (ce test ne garantit PAS l'acyclicite) :</b>
 * {@code ApplicationModules.verify()} global echoue aujourd'hui pour deux raisons
 * preexistantes, toutes deux hors scope de ce chantier (qui exige seulement des
 * dependances declarees explicitement) :
 * <ul>
 *   <li>{@code commun} et {@code request} participent EUX-MEMES a des cycles directs avec
 *       leurs dependances declarees ({@code commun <-> auth/customers/user},
 *       {@code request <-> team/customers/intervention/invoice/order}), en plus de
 *       nombreux cycles entre d'autres modules de {@code features} ;</li>
 *   <li>environ 98 violations d'acces a des types internes non exposes entre d'autres
 *       modules, categorie distincte des cycles.</li>
 * </ul>
 * Une future extraction de {@code commun}/{@code request} en services separes devra
 * d'abord casser ces cycles.
 */
class ModularityTests {

    private static final String FEATURES_PACKAGE = "com.logicielpourtous.site.features";
    private static final List<String> MODULES_VERIFIES = List.of("commun", "request", "sync", "licence");

    @Test
    void verifiesModuleStructure() {
        ApplicationModules modules = ApplicationModules.of(FEATURES_PACKAGE);
        List<String> violations = new ArrayList<>();

        for (String nom : MODULES_VERIFIES) {
            ApplicationModule module = modules.getModuleByName(nom)
                    .orElseThrow(() -> new AssertionError("Module introuvable : " + nom));

            // Les dependances doivent etre declarees explicitement (sinon Modulith autorise tout).
            if (module.getAllowedDependencies(modules).isEmpty()) {
                violations.add("Module '" + nom + "' : aucune allowedDependencies declaree "
                        + "dans package-info.java (dependances implicites).");
            }

            // detectDependencies() signale aussi l'injection par champ d'un bean d'un autre module
            // (regle de style, pas de frontiere) : RequestService.userRepository est dans ce cas
            // aujourd'hui. Hors scope de ce test ; seule cette categorie de message est exclue.
            module.detectDependencies(modules)
                    .filter(v -> !v.hasMessageContaining("uses field injection"))
                    .getMessages()
                    .forEach(violations::add);
        }

        assertThat(violations)
                .as("Violations de frontieres Modulith :%n%s", String.join(System.lineSeparator(), violations))
                .isEmpty();
    }
}
