import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';

export type ZoneNavigation = 'PUBLIQUE' | 'CLIENT' | 'PARTENAIRE' | 'ADMIN';

/** Tuile de l'accueil, déclarée par un plugin ACTIF (réponse de GET /api/navigation). */
export interface Tuile {
  plugin: string;
  libelle: string;
  icone: string;
  chemin: string;
  zone: ZoneNavigation;
  /** Version du manifeste du plugin actif : sert à charger (et recharger) son front. */
  version: string;
}

@Injectable({ providedIn: 'root' })
export class NavigationService {
  private readonly http = inject(HttpClient);

  /**
   * Tuiles autorisées pour l'utilisateur courant (société : celle de la session si absente). La ou les zones sont
   * décidées par le SERVEUR d'après les capacités de la société ; chaque tuile porte sa zone. Le front ne la devine pas.
   */
  tuilesAutorisees(societeId?: number): Observable<Tuile[]> {
    let params = new HttpParams();
    if (societeId !== undefined) params = params.set('societeId', societeId);
    return this.http.get<Tuile[]>('/api/navigation', { params });
  }
}

/** Lien Angular vers l'écran d'un plugin : toujours sous /plugins/<plugin>, quelle que soit la zone. */
export function lienPlugin(plugin: string, chemin: string): string[] {
  return ['/plugins', plugin, ...chemin.split('/').filter(s => s.length > 0)];
}
