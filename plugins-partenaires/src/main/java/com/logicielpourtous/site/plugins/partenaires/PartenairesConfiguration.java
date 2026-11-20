package com.logicielpourtous.site.plugins.partenaires;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/** Configuration du plugin « partenaires » : scanne uniquement son propre paquet (aucune classe du noyau). */
@Configuration
@ComponentScan("com.logicielpourtous.site.plugins.partenaires")
public class PartenairesConfiguration {

}
