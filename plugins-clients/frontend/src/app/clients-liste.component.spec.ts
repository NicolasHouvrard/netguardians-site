import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { ClientsApi } from './clients-api';
import { ClientsListeComponent } from './clients-liste.component';
import { Navigation } from './navigation';
import { simulerFetch } from './test-utils';

function monter() {
  TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
  TestBed.inject(ClientsApi).jeton.set('JT');
  const f = TestBed.createComponent(ClientsListeComponent);
  f.detectChanges();
  return f;
}
const texte = (f: { nativeElement: HTMLElement }) => f.nativeElement.textContent ?? '';

describe('ClientsListeComponent', () => {
  it('affiche les clients (echappes) avec le jeton en en-tete', async () => {
    const appels = simulerFetch({
      'GET /api/plugins/clients/liste': {
        status: 200,
        corps: [
          { societeId: 11, siren: '123456782', tva: null, origine: 'MANUEL' },
          { societeId: 12, siren: '<b>gras</b>', tva: 'FR1', origine: 'INSCRIPTION' },
        ],
      },
    });
    const f = monter();
    await vi.waitFor(() => {
      f.detectChanges();
      expect(texte(f)).toContain('123456782');
    });
    expect(texte(f)).toContain('<b>gras</b>'); // affiche tel quel, jamais interprete
    expect(f.nativeElement.querySelector('b')).toBeNull();
    expect(appels[0].authorization).toBe('Bearer JT');
    expect(appels[0].url).not.toContain('JT');
  });

  it('affiche le nom (societe ET particulier sans SIREN/TVA), echappe', async () => {
    simulerFetch({
      'GET /api/plugins/clients/liste': {
        status: 200,
        corps: [
          { societeId: 21, nom: 'Jeanne Martin', siren: '123456782', tva: null, origine: 'MANUEL' },
          { societeId: 22, nom: 'part@exemple.fr', siren: null, tva: null, origine: 'MANUEL' },
          { societeId: 23, nom: '<i>pirate</i>', siren: null, tva: null, origine: 'MANUEL' },
        ],
      },
    });
    const f = monter();
    await vi.waitFor(() => {
      f.detectChanges();
      expect(texte(f)).toContain('Jeanne Martin');
    });
    const lignes = Array.from(f.nativeElement.querySelectorAll('tbody tr')).map(tr => (tr as HTMLElement).textContent);
    expect(lignes[1]).toContain('part@exemple.fr'); // le particulier n'est plus une ligne vide
    expect(texte(f)).toContain('<i>pirate</i>');
    expect(f.nativeElement.querySelector('i')).toBeNull();
  });

  it('affiche un etat d erreur sur 403', async () => {
    simulerFetch({ 'GET /api/plugins/clients/liste': { status: 403 } });
    const f = monter();
    await vi.waitFor(() => {
      f.detectChanges();
      expect(f.nativeElement.querySelector('[role=alert]')?.textContent).toContain('droit');
    });
    expect(f.nativeElement.querySelectorAll('tbody tr').length).toBe(0);
  });

  it('ouvre une fiche et le formulaire de creation', async () => {
    simulerFetch({
      'GET /api/plugins/clients/liste': { status: 200, corps: [{ societeId: 11, siren: 'S', tva: null, origine: 'MANUEL' }] },
    });
    const f = monter();
    const nav = TestBed.inject(Navigation);
    await vi.waitFor(() => {
      f.detectChanges();
      expect(f.nativeElement.querySelector('tbody button')).not.toBeNull();
    });
    f.nativeElement.querySelector('tbody button').click();
    expect(nav.chemin()).toBe('/clients/11');
    f.nativeElement.querySelector('button.nouveau').click();
    expect(nav.chemin()).toBe('/clients/nouveau');
  });
});
