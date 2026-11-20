import { Injectable, signal } from '@angular/core';
import { IDENTIFIANT } from './navigation';

export interface Reponse<T = unknown> {
  /** Code HTTP ; 0 si le reseau a echoue ; -1 si la demande a ete refusee localement (aucun appel). */
  status: number;
  corps: T | null;
}

export interface PartenaireResume {
  societeId: number;
  siren: string | null;
  raisonSociale: string | null;
  etat: string | null;
}

export const MESSAGE_403 = "Accès réservé à l'administrateur du serveur maître";
export const MESSAGE_GENERIQUE = 'Une erreur est survenue. Réessayez plus tard.';
export const DROIT_VALIDE = /^[a-z][a-z0-9_.]{0,59}$/;

const BASE = '/api/plugins/partenaires';

/** Message affiche pour un statut d'erreur generique (jamais le corps serveur). */
export function messageErreur(status: number): string {
  return status === 403 ? MESSAGE_403 : MESSAGE_GENERIQUE;
}

/** Appels a l'API du plugin. Le jeton ne voyage que dans l'en-tete Authorization. */
@Injectable({ providedIn: 'root' })
export class PartenairesApi {
  readonly jeton = signal<string | null>(null);

  liste() {
    return this.requete<PartenaireResume[]>('GET', '/liste');
  }

  creerPartenaire(demande: object) {
    return this.requete<{ societeId?: number; message?: string }>('POST', '', demande);
  }

  creerClient(partenaireId: string, demande: object) {
    if (!IDENTIFIANT.test(partenaireId)) return Promise.resolve<Reponse<{ message?: string }>>({ status: -1, corps: null });
    return this.requete<{ message?: string }>('POST', `/${partenaireId}/clients`, demande);
  }

  modeleDroits() {
    return this.requete<string[]>('GET', '/modele-droits');
  }

  remplacerModeleDroits(droits: string[]) {
    return this.requete<unknown>('PUT', '/modele-droits', { droits });
  }

  private async requete<T>(methode: string, chemin: string, corps?: object): Promise<Reponse<T>> {
    const entetes: Record<string, string> = { Accept: 'application/json' };
    const jeton = this.jeton();
    if (jeton) entetes['Authorization'] = `Bearer ${jeton}`;
    if (corps !== undefined) entetes['Content-Type'] = 'application/json';
    try {
      const r = await fetch(BASE + chemin, {
        method: methode,
        headers: entetes,
        body: corps === undefined ? undefined : JSON.stringify(corps),
      });
      let json: T | null = null;
      try {
        json = (await r.json()) as T;
      } catch {
        json = null;
      }
      return { status: r.status, corps: json };
    } catch {
      return { status: 0, corps: null };
    }
  }
}
