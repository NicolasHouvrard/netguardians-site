package com.logicielpourtous.site.features.plateforme;

import java.util.List;

/**
 * Mesure des fuites de générations retirées : nombre de chargeurs retirés depuis le démarrage, combien sont encore
 * vivants après pression mémoire et GC (avec leurs étiquettes {@code nom@version#seq}), et Metaspace utilisé en Mo
 * ({@code -1} si la JVM n'expose pas ce pool).
 */
public record RapportFuites(int retires, int toujoursVivants, List<String> etiquettesVivantes, long metaspaceMo) {

	public RapportFuites {
		etiquettesVivantes = List.copyOf(etiquettesVivantes);
	}

}
