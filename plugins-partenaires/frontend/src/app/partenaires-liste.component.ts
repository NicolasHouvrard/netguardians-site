import { Component, inject, signal } from '@angular/core';
import { PartenaireResume, PartenairesApi, messageErreur } from './partenaires-api';
import { Navigation } from './navigation';

@Component({
  selector: 'partenaires-liste',
  standalone: true,
  template: `
    <h2>Partenaires</h2>
    <p>
      <button type="button" class="creer-partenaire" (click)="nav.aller('/partenaires/nouveau')">Créer un partenaire</button>
      <button type="button" class="modele" (click)="nav.aller('/partenaires/modele')">Modèle de droits par défaut</button>
    </p>
    @if (nav.message()) {
      <p role="status" class="succes">{{ nav.message() }}</p>
    }
    @if (erreur()) {
      <p role="alert" class="erreur">{{ erreur() }}</p>
    }
    @if (partenaires().length > 0) {
      <table>
        <thead><tr><th>Société</th><th>SIREN</th><th>Raison sociale</th><th>État</th><th></th></tr></thead>
        <tbody>
          @for (p of partenaires(); track p.societeId) {
            <tr>
              <td>{{ p.societeId }}</td>
              <td>{{ p.siren }}</td>
              <td>{{ p.raisonSociale }}</td>
              <td>{{ p.etat }}</td>
              <td><button type="button" class="creer-client" (click)="creerClient(p.societeId)">Créer un client</button></td>
            </tr>
          }
        </tbody>
      </table>
    } @else if (!erreur() && !enCours()) {
      <p>Aucun partenaire.</p>
    }
  `,
})
export class PartenairesListeComponent {
  private readonly api = inject(PartenairesApi);
  protected readonly nav = inject(Navigation);

  protected readonly partenaires = signal<PartenaireResume[]>([]);
  protected readonly erreur = signal<string | null>(null);
  protected readonly enCours = signal(true);

  constructor() {
    void this.charger();
  }

  protected creerClient(id: number): void {
    this.nav.aller('/partenaires/' + id + '/clients/nouveau');
  }

  private async charger(): Promise<void> {
    const r = await this.api.liste();
    this.enCours.set(false);
    if (r.status === 200 && Array.isArray(r.corps)) this.partenaires.set(r.corps);
    else this.erreur.set(messageErreur(r.status));
  }
}
