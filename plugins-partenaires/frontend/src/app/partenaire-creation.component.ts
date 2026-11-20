import { Component, inject, signal } from '@angular/core';
import { PartenairesApi, messageErreur } from './partenaires-api';
import { Navigation } from './navigation';

@Component({
  selector: 'partenaire-creation',
  standalone: true,
  template: `
    <p><button type="button" class="retour" (click)="nav.aller('/partenaires')">← Retour à la liste</button></p>
    <h2>Créer un partenaire</h2>
    <form class="partenaire" (submit)="envoyer($event)">
      <p><label>SIREN <input class="siren" [value]="siren()" (input)="siren.set(valeur($event))" inputmode="numeric" /></label></p>
      <p><label>N° de TVA (facultatif) <input class="tva" [value]="tva()" (input)="tva.set(valeur($event))" /></label></p>
      <p><label>Raison sociale <input class="raison" [value]="raison()" (input)="raison.set(valeur($event))" /></label></p>
      <p><label>E-mail <input class="email" type="email" [value]="email()" (input)="email.set(valeur($event))" /></label></p>
      <p><label>Nom <input class="nom" [value]="nom()" (input)="nom.set(valeur($event))" /></label></p>
      <p><button type="submit" [disabled]="enCours()">Créer le partenaire</button></p>
    </form>
    @if (erreur()) {
      <p role="alert" class="erreur">{{ erreur() }}</p>
    }
  `,
})
export class PartenaireCreationComponent {
  private readonly api = inject(PartenairesApi);
  protected readonly nav = inject(Navigation);

  protected readonly siren = signal('');
  protected readonly tva = signal('');
  protected readonly raison = signal('');
  protected readonly email = signal('');
  protected readonly nom = signal('');
  protected readonly enCours = signal(false);
  protected readonly erreur = signal<string | null>(null);

  protected valeur(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected async envoyer(e: Event): Promise<void> {
    e.preventDefault();
    if (this.enCours()) return;
    const tva = this.tva().trim();
    this.enCours.set(true);
    this.erreur.set(null);
    const r = await this.api.creerPartenaire({
      siren: this.siren().trim(),
      tva: tva === '' ? null : tva,
      raisonSociale: this.raison().trim(),
      email: this.email().trim(),
      nom: this.nom().trim(),
    });
    this.enCours.set(false);
    if (r.status === 201) this.nav.aller('/partenaires', 'Partenaire créé.');
    else if (r.status === 409) this.erreur.set(r.corps?.message ?? 'Ce partenaire existe déjà.');
    else if (r.status === 400) this.erreur.set('Données invalides : vérifiez les champs saisis.');
    else this.erreur.set(messageErreur(r.status));
  }
}
