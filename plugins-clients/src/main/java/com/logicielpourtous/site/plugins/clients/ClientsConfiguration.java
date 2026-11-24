package com.logicielpourtous.site.plugins.clients;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/** Configuration du plugin « clients » : scanne uniquement son propre paquet (aucune classe du noyau). */
@Configuration
@ComponentScan("com.logicielpourtous.site.plugins.clients")
public class ClientsConfiguration {

}
