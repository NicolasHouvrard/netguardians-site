import { Component, inject, signal } from '@angular/core';
import { ClientsApi } from './clients-api';
import { Navigation } from './navigation';

@Component({
  selector: 'clients-creation',
  standalone: true,
  template: `
    <p><button type="button" class="retour" (click)="nav.aller('/clients')">← Retour à la liste</button></p>
    <h2>Nouveau client</h2>
    <form (submit)="envoyer($event)">
      <fieldset>
        <legend>Type de client</legend>
        <label><input type="radio" name="type" value="societe" [checked]="type() === 'societe'" (click)="type.set('societe')" /> Société</label>
        <label><input type="radio" name="type" value="particulier" [checked]="type() === 'particulier'" (click)="type.set('particulier')" /> Particulier</label>
      </fieldset>
      @if (type() === 'societe') {
        <p>
          <label><input type="checkbox" class="etrangere" [checked]="etrangere()" (click)="etrangere.set(!etrangere())" /> Société étrangère</label>
        </p>
        @if (!etrangere()) {
          <p><label>SIREN <input class="siren" [value]="siren()" (input)="siren.set(valeur($event))" inputmode="numeric" /></label></p>
        } @else {
          <p><label>N° de TVA <input class="tva" [value]="tva()" (input)="tva.set(valeur($event))" /></label></p>
        }
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
export class ClientsCreationComponent {
  private readonly api = inject(ClientsApi);
  protected readonly nav = inject(Navigation);

  protected readonly type = signal<'societe' | 'particulier' | null>(null);
  protected readonly etrangere = signal(false);
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
    const etrangere = societe && this.etrangere();
    const vide = (v: string) => (v.trim() === '' ? null : v.trim());
    const demande = {
      particulier: !societe,
      siren: societe && !etrangere ? vide(this.siren()) : null,
      tva: etrangere ? vide(this.tva()) : null,
      etrangere,
      email: this.email().trim(),
      nom: this.nom().trim(),
    };
    this.enCours.set(true);
    this.erreur.set(null);
    const r = await this.api.creer(demande);
    this.enCours.set(false);
    if (r.status === 201 && r.corps?.societeId != null) {
      this.nav.aller('/clients/' + r.corps.societeId);
    } else if (r.status === 409) {
      this.erreur.set(r.corps?.message ?? 'Ce client existe déjà.');
    } else if (r.status === 403) {
      this.erreur.set("Accès refusé : vous n'avez pas le droit de créer un client.");
    } else if (r.status === 400) {
      this.erreur.set('Données invalides : vérifiez les champs saisis.');
    } else {
      this.erreur.set('La création a échoué. Réessayez plus tard.');
    }
  }
}
