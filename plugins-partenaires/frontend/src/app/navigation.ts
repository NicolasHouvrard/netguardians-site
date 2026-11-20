import { Injectable, signal } from '@angular/core';

export type Ecran =
  | { ecran: 'liste' }
  | { ecran: 'creation' }
  | { ecran: 'modele' }
  | { ecran: 'client'; partenaireId: string }
  | { ecran: 'inconnu' };

export const IDENTIFIANT = /^[0-9]{1,15}$/;

/**
 * Chemins internes : `/partenaires`, `/partenaires/nouveau`, `/partenaires/modele`,
 * `/partenaires/<id>/clients/nouveau`. Un identifiant n'est accepte que s'il est strictement numerique.
 */
export function resoudre(chemin: string | null | undefined): Ecran {
  const s = (chemin ?? '').split('/').filter(x => x.length > 0);
  if (s.length === 0) return { ecran: 'liste' };
  if (s[0] !== 'partenaires') return { ecran: 'inconnu' };
  if (s.length === 1) return { ecran: 'liste' };
  if (s.length === 2 && s[1] === 'nouveau') return { ecran: 'creation' };
  if (s.length === 2 && s[1] === 'modele') return { ecran: 'modele' };
  if (s.length === 4 && s[2] === 'clients' && s[3] === 'nouveau' && IDENTIFIANT.test(s[1])) {
    return { ecran: 'client', partenaireId: s[1] };
  }
  return { ecran: 'inconnu' };
}

/** Chemin courant dans le plugin (resynchronise par la propriete `chemin`) et message de succes a afficher. */
@Injectable({ providedIn: 'root' })
export class Navigation {
  readonly chemin = signal('/partenaires');
  readonly message = signal<string | null>(null);

  aller(chemin: string, message: string | null = null): void {
    this.message.set(message);
    this.chemin.set(chemin);
  }
}
