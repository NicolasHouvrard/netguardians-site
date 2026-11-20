import { Component, ViewEncapsulation, computed, effect, inject, input } from '@angular/core';
import { PartenairesApi } from './partenaires-api';
import { ModeleDroitsComponent } from './modele-droits.component';
import { Navigation, resoudre } from './navigation';
import { PartenaireClientCreationComponent } from './partenaire-client-creation.component';
import { PartenaireCreationComponent } from './partenaire-creation.component';
import { PartenairesListeComponent } from './partenaires-liste.component';

/**
 * Racine de l'element personnalise : proprietes `chemin` (route interne) et `jeton` (Bearer), poussees par le noyau.
 * Styles isoles dans un shadow root.
 */
@Component({
  selector: 'partenaires-app',
  standalone: true,
  encapsulation: ViewEncapsulation.ShadowDom,
  imports: [PartenairesListeComponent, PartenaireCreationComponent, PartenaireClientCreationComponent, ModeleDroitsComponent],
  styles: [
    `
      :host { display: block; font-family: inherit; padding: 16px; }
      table { border-collapse: collapse; width: 100%; }
      th, td { text-align: left; padding: 6px 10px; border-bottom: 1px solid #ddd; }
      .erreur { color: #b3261e; }
      .succes { color: #1b6e2d; }
      fieldset { border: 1px solid #ccc; margin-bottom: 12px; }
      label { margin-right: 12px; }
      input { margin-left: 4px; }
    `,
  ],
  template: `
    @switch (ecran().ecran) {
      @case ('liste') { <partenaires-liste /> }
      @case ('creation') { <partenaire-creation /> }
      @case ('modele') { <modele-droits /> }
      @case ('client') { <partenaire-client-creation [partenaireId]="partenaireId()" /> }
      @default { <p role="alert" class="erreur">Page introuvable.</p> }
    }
  `,
})
export class PartenairesAppComponent {
  readonly chemin = input<string>('/partenaires');
  readonly jeton = input<string | null>(null);

  private readonly nav = inject(Navigation);
  private readonly api = inject(PartenairesApi);

  protected readonly ecran = computed(() => resoudre(this.nav.chemin()));
  protected readonly partenaireId = computed(() => {
    const e = this.ecran();
    return e.ecran === 'client' ? e.partenaireId : '';
  });

  constructor() {
    effect(() => this.api.jeton.set(this.jeton()));
    effect(() => this.nav.aller(this.chemin()));
  }
}
