package com.logicielpourtous.site.features.commun;

/**
 * Reglages du compte unique (Authelia) de ce serveur. Interface de {@code commun} implementee par le module
 * {@code auth} : la valeur enregistree en base prime, sinon la variable d'environnement historique, sinon vide.
 * Toutes les valeurs absentes valent {@code ""} (jamais {@code null}).
 * <p>
 * {@link #clientSecret()} est un secret : ne jamais le journaliser ni le placer dans un message ou une reponse.
 */
public interface ConfigurationCompteUnique {

	/** Vrai si l'emetteur, l'identifiant et le secret du client sont tous renseignes. */
	boolean configure();

	String issuerUri();

	String clientId();

	/** Secret en clair (dechiffre) ; a ne jamais exposer. */
	String clientSecret();

	/** Adresse publique de CE serveur. */
	String urlPublique();

	/** Valeur explicite si valide, sinon {@code urlPublique} + {@code /login?logout} si valide, sinon vide. */
	String retourDeconnexion();

	/** Adresse publique du Maitre (aiguillage) ; vide hors Maitre. */
	String maitreUrlPublique();

	/** Compteur change a chaque enregistrement : les consommateurs se reconstruisent quand il change. */
	long version();
}
