import { Component, inject, signal } from '@angular/core';
import { DROIT_VALIDE, PartenairesApi, messageErreur } from './partenaires-api';
import { Navigation } from './navigation';

@Component({
  selector: 'modele-droits',
  standalone: true,
  template: `
    <p><button type="button" class="retour" (click)="nav.aller('/partenaires')">← Retour à la liste</button></p>
    <h2>Modèle de droits par défaut</h2>
    <ul>
      @for (d of droits(); track d) {
        <li>{{ d }} <button type="button" class="retirer" [attr.data-droit]="d" (click)="retirer(d)">Retirer</button></li>
      }
    </ul>
    <p>
      <label>Nouveau droit <input class="nouveau" [value]="nouveau()" (input)="nouveau.set(valeur($event))" /></label>
      <button type="button" class="ajouter" (click)="ajouter()">Ajouter</button>
    </p>
    <p><button type="button" class="enregistrer" [disabled]="enCours()" (click)="enregistrer()">Enregistrer</button></p>
    @if (succes()) {
      <p role="status" class="succes">Modèle enregistré.</p>
    }
    @if (erreur()) {
      <p role="alert" class="erreur">{{ erreur() }}</p>
    }
  `,
})
export class ModeleDroitsComponent {
  private readonly api = inject(PartenairesApi);
  protected readonly nav = inject(Navigation);

  protected readonly droits = signal<string[]>([]);
  protected readonly nouveau = signal('');
  protected readonly enCours = signal(false);
  protected readonly erreur = signal<string | null>(null);
  protected readonly succes = signal(false);

  constructor() {
    void this.charger();
  }

  protected valeur(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected ajouter(): void {
    const d = this.nouveau().trim();
    this.succes.set(false);
    if (!DROIT_VALIDE.test(d)) {
      this.erreur.set('Droit invalide : minuscules, chiffres, « _ » et « . », 60 caractères au plus, première lettre obligatoire.');
      return;
    }
    this.erreur.set(null);
    if (!this.droits().includes(d)) this.droits.update(l => [...l, d]);
    this.nouveau.set('');
  }

  protected retirer(d: string): void {
    this.succes.set(false);
    this.droits.update(l => l.filter(x => x !== d));
  }

  protected async enregistrer(): Promise<void> {
    if (this.enCours()) return;
    this.succes.set(false);
    if (this.droits().length === 0) {
      this.erreur.set('Le modèle ne peut pas être vide.');
      return;
    }
    this.enCours.set(true);
    this.erreur.set(null);
    const r = await this.api.remplacerModeleDroits(this.droits());
    this.enCours.set(false);
    if (r.status === 204 || r.status === 200) this.succes.set(true);
    else this.erreur.set(r.status === 400 ? 'Données invalides : vérifiez les droits saisis.' : messageErreur(r.status));
  }

  private async charger(): Promise<void> {
    const r = await this.api.modeleDroits();
    if (r.status === 200 && Array.isArray(r.corps)) this.droits.set(r.corps.filter(d => typeof d === 'string'));
    else this.erreur.set(messageErreur(r.status));
  }
}
