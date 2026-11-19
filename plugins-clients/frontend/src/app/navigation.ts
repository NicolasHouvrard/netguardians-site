import { Injectable, signal } from '@angular/core';

export type Ecran = { ecran: 'liste' } | { ecran: 'creation' } | { ecran: 'fiche'; societeId: number } | { ecran: 'inconnu' };

/**
 * Chemins internes du plugin : `/clients` (liste), `/clients/nouveau` (creation), `/clients/<id>` (fiche).
 * Un identifiant n'est accepte que s'il est strictement numerique : rien d'autre ne part vers l'API.
 */
export function resoudre(chemin: string | null | undefined): Ecran {
  const segments = (chemin ?? '').split('/').filter(s => s.length > 0);
  if (segments.length === 0) return { ecran: 'liste' };
  if (segments[0] !== 'clients' || segments.length > 2) return { ecran: 'inconnu' };
  if (segments.length === 1) return { ecran: 'liste' };
  if (segments[1] === 'nouveau') return { ecran: 'creation' };
  if (/^[0-9]{1,15}$/.test(segments[1])) return { ecran: 'fiche', societeId: Number(segments[1]) };
  return { ecran: 'inconnu' };
}

/**
 * Chemin courant a l'interieur du plugin. Initialise et resynchronise par la propriete `chemin` de l'element
 * (pilotee par le noyau) ; la navigation interne le modifie localement, sans toucher a l'URL du noyau.
 */
@Injectable({ providedIn: 'root' })
export class Navigation {
  readonly chemin = signal('/clients');

  aller(chemin: string): void {
    this.chemin.set(chemin);
  }
}
