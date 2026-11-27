package com.logicielpourtous.site.features.commun;

/**
 * Contrat {@link ConfigurationCompteUnique} en memoire pour les tests : chaque modification incremente la version,
 * comme un enregistrement en base. Valeurs absentes = vide.
 */
public class ConfigurationCompteUniqueFixe implements ConfigurationCompteUnique {

    private volatile String issuer = "";
    private volatile String clientId = "";
    private volatile String secret = "";
    private volatile String urlPublique = "";
    private volatile String retour = "";
    private volatile String maitre = "";
    private volatile long version;

    public static ConfigurationCompteUniqueFixe vide() {
        return new ConfigurationCompteUniqueFixe();
    }

    public static ConfigurationCompteUniqueFixe complete(String issuer, String clientId, String secret) {
        return new ConfigurationCompteUniqueFixe().compte(issuer, clientId, secret);
    }

    /** Remplace le compte unique (emetteur, identifiant, secret) et change la version. */
    public synchronized ConfigurationCompteUniqueFixe compte(String issuer, String clientId, String secret) {
        this.issuer = issuer;
        this.clientId = clientId;
        this.secret = secret;
        version++;
        return this;
    }

    public synchronized ConfigurationCompteUniqueFixe retour(String retour) {
        this.retour = retour;
        version++;
        return this;
    }

    public synchronized ConfigurationCompteUniqueFixe maitre(String maitre) {
        this.maitre = maitre;
        version++;
        return this;
    }

    @Override
    public boolean configure() {
        return !issuer.isEmpty() && !clientId.isEmpty() && !secret.isEmpty();
    }

    @Override
    public String issuerUri() {
        return issuer;
    }

    @Override
    public String clientId() {
        return clientId;
    }

    @Override
    public String clientSecret() {
        return secret;
    }

    @Override
    public String urlPublique() {
        return urlPublique;
    }

    @Override
    public String retourDeconnexion() {
        return retour;
    }

    @Override
    public String maitreUrlPublique() {
        return maitre;
    }

    @Override
    public long version() {
        return version;
    }

    @Override
    public String toString() {
        return "ConfigurationCompteUniqueFixe[version=" + version + "]";
    }
}
