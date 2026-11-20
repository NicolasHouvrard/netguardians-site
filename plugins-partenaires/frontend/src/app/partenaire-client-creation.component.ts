import { Component, inject, input, signal } from '@angular/core';
import { PartenairesApi, messageErreur } from './partenaires-api';
import { Navigation } from './navigation';

@Component({
  selector: 'partenaire-client-creation',
  standalone: true,
  template: `
    <p><button type="button" class="retour" (click)="nav.aller('/partenaires')">← Retour à la liste</button></p>
    <h2>Créer un client pour ce partenaire</h2>
    <form (submit)="envoyer($event)">
      <fieldset>
        <legend>Type de client</legend>
        <label><input type="radio" name="type" value="societe" [checked]="type() === 'societe'" (click)="type.set('societe')" /> Société</label>
        <label><input type="radio" name="type" value="particulier" [checked]="type() === 'particulier'" (click)="type.set('particulier')" /> Particulier</label>
      </fieldset>
      @if (type() === 'societe') {
        <p><label>SIREN <input class="siren" [value]="siren()" (input)="siren.set(valeur($event))" inputmode="numeric" /></label></p>
        <p><label>N° de TVA <input class="tva" [value]="tva()" (input)="tva.set(valeur($event))" /></label></p>
      }
      @if (type() !== null) {
        <p><label>Nom du contact <input class="nom" [value]="nom()" (input)="nom.set(valeur($event))" /></label></p>
        <p><label>E-mail du contact <input class="email" type="email" [value]="email()" (input)="email.set(valeur($event))" /></label></p>
        <p><button type="submit" [disabled]="enCours()">Créer le client</button></p>
      }
    </form>
    @if (erreur()) {
      <p role="alert" class="erreur">{{ erreur() }}</p>
    }
  `,
})
export class PartenaireClientCreationComponent {
  readonly partenaireId = input.required<string>();

  private readonly api = inject(PartenairesApi);
  protected readonly nav = inject(Navigation);

  protected readonly type = signal<'societe' | 'particulier' | null>(null);
  protected readonly siren = signal('');
  protected readonly tva = signal('');
  protected readonly nom = signal('');
  protected readonly email = signal('');
  protected readonly enCours = signal(false);
  protected readonly erreur = signal<string | null>(null);

  protected valeur(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected async envoyer(e: Event): Promise<void> {
    e.preventDefault();
    const type = this.type();
    if (type === null || this.enCours()) return;
    const societe = type === 'societe';
    const vide = (v: string) => (v.trim() === '' ? null : v.trim());
    // Le partenaire cible vient du chemin : jamais du corps.
    const demande = {
      particulier: !societe,
      siren: societe ? vide(this.siren()) : null,
      tva: societe ? vide(this.tva()) : null,
      email: this.email().trim(),
      nom: this.nom().trim(),
    };
    this.enCours.set(true);
    this.erreur.set(null);
    const r = await this.api.creerClient(this.partenaireId(), demande);
    this.enCours.set(false);
    if (r.status === 201) this.nav.aller('/partenaires', 'Client créé.');
    else if (r.status === 409) this.erreur.set(r.corps?.message ?? 'Ce client existe déjà.');
    else if (r.status === 400 || r.status === -1) this.erreur.set('Données invalides : vérifiez les champs saisis.');
    else this.erreur.set(messageErreur(r.status));
  }
}
