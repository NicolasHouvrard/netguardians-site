import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { AuthService } from '../auth/auth.service';

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
  private readonly auth = inject(AuthService);

  /** Tuiles autorisées pour l'utilisateur courant dans cette zone (société : celle de la session si absente). */
  tuiles(zone: string, societeId?: number): Observable<Tuile[]> {
    let params = new HttpParams().set('zone', zone);
    if (societeId !== undefined) params = params.set('societeId', societeId);
    return this.http.get<Tuile[]>('/api/navigation', { params });
  }

  /** Zone de l'utilisateur : un compte portail pur est dans l'espace client, tout autre compte côté partenaire. */
  zoneCourante(): ZoneNavigation {
    return this.auth.isPortalUser() && !this.auth.isEsnUser() ? 'CLIENT' : 'PARTENAIRE';
  }
}

/** Lien Angular vers l'écran d'un plugin : toujours sous /plugins/<plugin>, quelle que soit la zone. */
export function lienPlugin(plugin: string, chemin: string): string[] {
  return ['/plugins', plugin, ...chemin.split('/').filter(s => s.length > 0)];
}
