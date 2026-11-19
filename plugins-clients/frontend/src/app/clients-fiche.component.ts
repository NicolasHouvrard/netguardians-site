import { Component, effect, inject, input, signal, untracked } from '@angular/core';
import { ClientResume, ClientsApi, FicheClient } from './clients-api';
import { Navigation } from './navigation';

@Component({
  selector: 'clients-fiche',
  standalone: true,
  template: `
    <p><button type="button" class="retour" (click)="nav.aller('/clients')">← Retour à la liste</button></p>
    <h2>Fiche client</h2>
    @if (erreur()) {
      <p role="alert" class="erreur">{{ erreur() }}</p>
    } @else if (fiche() === null) {
      <p>Chargement…</p>
    } @else {
      <dl>
        <dt>SIREN</dt><dd>{{ fiche()!.resume.siren }}</dd>
        <dt>TVA</dt><dd>{{ fiche()!.resume.tva }}</dd>
        <dt>Origine</dt><dd>{{ fiche()!.resume.origine }}</dd>
        <dt>Capacités</dt><dd>{{ fiche()!.capacites.join(', ') }}</dd>
      </dl>
      @if (resume(); as r) {
        <section class="bloc">
          <h3>Résumé</h3>
          <p>SIREN {{ r.siren }} · TVA {{ r.tva }} · origine {{ r.origine }}</p>
        </section>
      }
    }
  `,
})
export class ClientsFicheComponent {
  private readonly api = inject(ClientsApi);
  protected readonly nav = inject(Navigation);

  readonly societeId = input.required<number>();

  protected readonly fiche = signal<FicheClient | null>(null);
  protected readonly resume = signal<ClientResume | null>(null);
  protected readonly erreur = signal<string | null>(null);

  /** Numero de chargement : une reponse d'une fiche precedente (changement d'identifiant) est ignoree. */
  private numero = 0;

  constructor() {
    effect(() => {
      const id = this.societeId();
      this.api.jeton(); // recharge aussi si le jeton arrive ou change
      untracked(() => void this.charger(id));
    });
  }

  private async charger(id: number): Promise<void> {
    const mon = ++this.numero;
    this.fiche.set(null);
    this.resume.set(null);
    this.erreur.set(null);
    const [f, b] = await Promise.all([this.api.fiche(id), this.api.blocResume(id)]);
    if (mon !== this.numero) return;
    if (f.status === 200 && f.corps) {
      this.fiche.set(f.corps);
      if (b.status === 200) this.resume.set(b.corps);
    } else if (f.status === 403) {
      this.erreur.set("Accès refusé : vous n'avez pas le droit de consulter cette fiche.");
    } else if (f.status === 404) {
      this.erreur.set('Client introuvable.');
    } else {
      this.erreur.set('Impossible de charger la fiche.');
    }
  }
}
