-- Reglages du compte unique (Authelia) de CE serveur : UNE seule ligne (id = 1).
-- client_secret est stocke chiffre (AES-256-GCM, jamais en clair). Une colonne vide = pas de valeur en base (l'environnement s'applique).
-- version : compteur incremente a chaque enregistrement, pour que les consommateurs se reconstruisent a chaud. Idempotent.
CREATE TABLE IF NOT EXISTS reglages_compte_unique (
    id                  BIGINT PRIMARY KEY CHECK (id = 1),
    issuer_uri          VARCHAR(300),
    client_id           VARCHAR(200),
    client_secret       TEXT,
    url_publique        VARCHAR(300),
    maitre_url_publique VARCHAR(300),
    retour_deconnexion  VARCHAR(500),
    version             BIGINT NOT NULL DEFAULT 0,
    modifie_par         VARCHAR(200),
    modifie_a           TIMESTAMPTZ NOT NULL DEFAULT now()
);
