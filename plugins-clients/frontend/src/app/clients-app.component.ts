import { Component, ViewEncapsulation, computed, effect, inject, input } from '@angular/core';
import { ClientsApi } from './clients-api';
import { ClientsCreationComponent } from './clients-creation.component';
import { ClientsFicheComponent } from './clients-fiche.component';
import { ClientsListeComponent } from './clients-liste.component';
import { Navigation, resoudre } from './navigation';

/**
 * Racine de l'element personnalise : proprietes `chemin` (route interne) et `jeton` (Bearer), poussees par le noyau.
 * Styles isoles dans un shadow root : le plugin n'herite ni n'affecte les styles du noyau.
 */
@Component({
  selector: 'clients-app',
  standalone: true,
  encapsulation: ViewEncapsulation.ShadowDom,
  imports: [ClientsListeComponent, ClientsFicheComponent, ClientsCreationComponent],
  styles: [
    `
      :host { display: block; font-family: inherit; padding: 16px; }
      table { border-collapse: collapse; width: 100%; }
      th, td { text-align: left; padding: 6px 10px; border-bottom: 1px solid #ddd; }
      .erreur { color: #b3261e; }
      fieldset { border: 1px solid #ccc; margin-bottom: 12px; }
      label { margin-right: 12px; }
      input { margin-left: 4px; }
    `,
  ],
  template: `
    @switch (ecran().ecran) {
      @case ('liste') { <clients-liste /> }
      @case ('creation') { <clients-creation /> }
      @case ('fiche') { <clients-fiche [societeId]="societeId()" /> }
      @default { <p role="alert" class="erreur">Page introuvable.</p> }
    }
  `,
})
export class ClientsAppComponent {
  readonly chemin = input<string>('/clients');
  readonly jeton = input<string | null>(null);

  private readonly nav = inject(Navigation);
  private readonly api = inject(ClientsApi);

  protected readonly ecran = computed(() => resoudre(this.nav.chemin()));
  protected readonly societeId = computed(() => {
    const e = this.ecran();
    return e.ecran === 'fiche' ? e.societeId : 0;
  });

  constructor() {
    effect(() => this.api.jeton.set(this.jeton()));
    effect(() => this.nav.aller(this.chemin()));
  }
}
