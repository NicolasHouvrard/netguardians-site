import { Component, OnInit, inject, signal } from '@angular/core';
import { ClientResume, ClientsApi } from './clients-api';
import { Navigation } from './navigation';

@Component({
  selector: 'clients-liste',
  standalone: true,
  template: `
    <h2>Clients</h2>
    <p><button type="button" class="nouveau" (click)="nav.aller('/clients/nouveau')">Nouveau client</button></p>
    @if (erreur()) {
      <p role="alert" class="erreur">{{ erreur() }}</p>
    } @else if (clients() === null) {
      <p>Chargement…</p>
    } @else if (clients()!.length === 0) {
      <p>Aucun client pour le moment.</p>
    } @else {
      <table>
        <thead>
          <tr><th>SIREN</th><th>TVA</th><th>Origine</th><th></th></tr>
        </thead>
        <tbody>
          @for (c of clients(); track c.societeId) {
            <tr>
              <td>{{ c.siren }}</td>
              <td>{{ c.tva }}</td>
              <td>{{ c.origine }}</td>
              <td><button type="button" (click)="nav.aller('/clients/' + c.societeId)">Ouvrir</button></td>
            </tr>
          }
        </tbody>
      </table>
    }
  `,
})
export class ClientsListeComponent implements OnInit {
  private readonly api = inject(ClientsApi);
  protected readonly nav = inject(Navigation);
  protected readonly clients = signal<ClientResume[] | null>(null);
  protected readonly erreur = signal<string | null>(null);

  async ngOnInit(): Promise<void> {
    const r = await this.api.liste();
    if (r.status === 200 && Array.isArray(r.corps)) {
      this.clients.set(r.corps);
    } else if (r.status === 403) {
      this.erreur.set("Accès refusé : vous n'avez pas le droit de consulter les clients.");
    } else {
      this.erreur.set('Impossible de charger les clients.');
    }
  }
}
