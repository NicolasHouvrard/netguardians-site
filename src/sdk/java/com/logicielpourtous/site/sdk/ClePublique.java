package com.logicielpourtous.site.sdk;

import java.security.PublicKey;

/** Clé publique de confiance, identifiée par son {@code keyId} (comparé de façon exacte). */
public record ClePublique(String keyId, PublicKey cle) {
}
