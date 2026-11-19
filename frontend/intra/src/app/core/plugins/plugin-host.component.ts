import { Component, ElementRef, OnInit, DestroyRef, inject, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Route, UrlMatchResult, UrlSegment } from '@angular/router';
import { catchError, from, of, switchMap } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { NavigationService, Tuile } from '../navigation/navigation.service';
import { PluginLoaderService } from './plugin-loader.service';

/** Route `plugins/:plugin/**` : tous les segments suivants sont consommés (le chemin interne appartient au plugin). */
export function pluginsMatcher(segments: UrlSegment[]): UrlMatchResult | null {
  if (segments.length < 2 || segments[0].path !== 'plugins') return null;
  return { consumed: segments, posParams: { plugin: segments[1] } };
}

export const ROUTE_PLUGINS: Route = {
  matcher: pluginsMatcher,
  loadComponent: () => import('./plugin-host.component').then(m => m.PluginHostComponent),
};

type ElementPlugin = HTMLElement & { chemin?: string; jeton?: string | null };

/**
 * Héberge le front d'un plugin : crée son élément personnalisé, lui passe le `chemin` interne et le `jeton`
 * d'authentification (propriétés), et le recrée quand la version active du plugin change.
 */
@Component({
  selector: 'app-plugin-host',
  standalone: true,
  template: `
    @if (erreur()) {
      <p class="erreur" role="alert">{{ erreur() }}</p>
    }
    <div #conteneur class="plugin-host"></div>
  `,
  styles: [`.plugin-host { height: 100%; } .erreur { padding: 16px; color: #b3261e; }`],
})
export class PluginHostComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly navigation = inject(NavigationService);
  private readonly loader = inject(PluginLoaderService);
  private readonly auth = inject(AuthService);
  private readonly destroyRef = inject(DestroyRef);

  private readonly conteneur = viewChild.required<ElementRef<HTMLElement>>('conteneur');
  protected readonly erreur = signal<string | null>(null);

  /** Numéro de la dernière navigation : un afficher() plus ancien, resté en attente du chargement, s'abandonne. */
  private numeroNavigation = 0;
  private nomCourant: string | null = null;
  private element: ElementPlugin | null = null;

  ngOnInit(): void {
    this.route.url
      .pipe(
        switchMap(segments => {
          const plugin = segments[1]?.path ?? '';
          const chemin = '/' + segments.slice(2).map(s => s.path).join('/');
          // Interrogé à chaque navigation : une mise à jour à chaud change la version active.
          return this.navigation.tuiles(this.navigation.zoneCourante()).pipe(
            switchMap(tuiles => from(this.afficher(plugin, chemin, tuiles, ++this.numeroNavigation))),
            catchError(() => {
              this.erreur.set('Application indisponible.');
              return of(undefined);
            }),
          );
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe();
  }

  private async afficher(plugin: string, chemin: string, tuiles: Tuile[], numero: number): Promise<void> {
    const tuile = tuiles.find(t => t.plugin === plugin);
    if (!tuile) {
      this.vider();
      this.erreur.set('Application introuvable ou non autorisée.');
      return;
    }
    this.erreur.set(null);
    const nom = await this.loader.charger(plugin, tuile.version);
    if (numero !== this.numeroNavigation) return; // une navigation plus récente a pris le relais pendant le chargement
    if (!customElements.get(nom)) {
      this.vider();
      this.erreur.set(`L'application ${plugin} ne déclare pas l'élément ${nom}.`);
      return;
    }
    if (this.element && this.nomCourant === nom) {
      this.element.chemin = chemin;
      this.element.jeton = this.auth.token();
      return;
    }
    this.vider();
    const el = document.createElement(nom) as ElementPlugin;
    el.chemin = chemin;
    el.jeton = this.auth.token();
    this.conteneur().nativeElement.appendChild(el);
    this.element = el;
    this.nomCourant = nom;
  }

  private vider(): void {
    this.element?.remove();
    this.element = null;
    this.nomCourant = null;
  }
}
