import { Injectable, signal } from '@angular/core';

export interface Reponse<T = unknown> {
  /** Code HTTP ; 0 si le reseau a echoue. */
  status: number;
  corps: T | null;
}

export interface ClientResume {
  societeId: number;
  /** Nom affiché (nom du premier membre ou e-mail ; le registre ne stocke pas de nom de société) ; nul sans membre. */
  nom?: string | null;
  siren: string | null;
  tva: string | null;
  origine: string | null;
}

export interface FicheClient {
  resume: ClientResume;
  partenaireSocieteId: number;
  capacites: string[];
}

const BASE = '/api/plugins/clients';

/** Appels a l'API du plugin. Le jeton ne voyage que dans l'en-tete Authorization : jamais dans une URL ni un journal. */
@Injectable({ providedIn: 'root' })
export class ClientsApi {
  readonly jeton = signal<string | null>(null);

  liste() {
    return this.requete<ClientResume[]>('GET', '/liste');
  }

  fiche(societeId: number) {
    return this.requete<FicheClient>('GET', `/${societeId}/fiche`);
  }

  blocResume(societeId: number) {
    return this.requete<ClientResume>('GET', `/${societeId}/blocs/resume`);
  }

  creer(demande: object) {
    return this.requete<{ issue?: string; societeId?: number; message?: string }>('POST', '', demande);
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
        json = null; // corps vide (403, 404) ou non JSON
      }
      return { status: r.status, corps: json };
    } catch {
      return { status: 0, corps: null };
    }
  }
}
