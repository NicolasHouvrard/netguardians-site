package com.logicielpourtous.site.features.commun;

import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Role de serveur configure via la propriete {@code app.role}.
 *
 * <p>Le setter accepte volontairement une valeur brute ({@code String}) plutot que
 * {@link ServerRole} directement : cela empeche le binder Spring d'invoquer sa conversion
 * String -&gt; enum par defaut, qui leverait une exception (et ferait planter le demarrage)
 * si la valeur configuree ne correspond a aucune des 4 constantes. La conversion est donc
 * faite ici, a la main (normalisation de casse incluse), avec un repli explicite sur
 * {@link ServerRole#MASTER} si la valeur est absente ou invalide.
 */
@Component
@ConfigurationProperties(prefix = "app")
public class ServerRoleProperties {

    private static final Logger log = LoggerFactory.getLogger(ServerRoleProperties.class);

    private ServerRole role = ServerRole.MASTER;

    public ServerRole getRole() {
        return role;
    }

    public void setRole(String rawRole) {
        if (rawRole == null || rawRole.isBlank()) {
            this.role = ServerRole.MASTER;
            return;
        }
        try {
            this.role = ServerRole.valueOf(rawRole.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Valeur invalide pour app.role : '{}' ne correspond a aucun ServerRole connu "
                    + "({}). Repli sur MASTER.", rawRole, Arrays.toString(ServerRole.values()));
            this.role = ServerRole.MASTER;
        }
    }
}
