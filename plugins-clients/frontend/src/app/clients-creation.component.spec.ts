import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { ClientsApi } from './clients-api';
import { ClientsCreationComponent } from './clients-creation.component';
import { Navigation } from './navigation';
import { simulerFetch } from './test-utils';

type Fixture = { nativeElement: HTMLElement; detectChanges(): void };

function monter() {
  TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
  TestBed.inject(ClientsApi).jeton.set('JT');
  const f = TestBed.createComponent(ClientsCreationComponent);
  f.detectChanges();
  return f;
}
function saisir(f: Fixture, sel: string, v: string) {
  const i = f.nativeElement.querySelector(sel) as HTMLInputElement;
  i.value = v;
  i.dispatchEvent(new Event('input'));
  f.detectChanges();
}
function cliquer(f: Fixture, sel: string) {
  (f.nativeElement.querySelector(sel) as HTMLElement).click();
  f.detectChanges();
}

describe('ClientsCreationComponent', () => {
  it('particulier : envoie particulier=true sans siren ni tva, puis ouvre la fiche creee', async () => {
    const appels = simulerFetch({ 'POST /api/plugins/clients': { status: 201, corps: { issue: 'CREE', societeId: 55 } } });
    const f = monter();
    cliquer(f, 'input[value=particulier]');
    expect(f.nativeElement.querySelector('.siren')).toBeNull();
    saisir(f, '.email', 'a@b.fr');
    saisir(f, '.nom', 'Alice');
    cliquer(f, 'button[type=submit]');
    await vi.waitFor(() => expect(TestBed.inject(Navigation).chemin()).toBe('/clients/55'));
    expect(JSON.parse(appels[0].corps!)).toEqual({
      particulier: true,
      siren: null,
      tva: null,
      etrangere: false,
      email: 'a@b.fr',
      nom: 'Alice',
    });
    expect(appels[0].authorization).toBe('Bearer JT');
  });

  it('societe francaise : envoie le SIREN', async () => {
    const appels = simulerFetch({ 'POST /api/plugins/clients': { status: 201, corps: { issue: 'CREE', societeId: 56 } } });
    const f = monter();
    cliquer(f, 'input[value=societe]');
    saisir(f, '.siren', '123456782');
    saisir(f, '.email', 'a@b.fr');
    saisir(f, '.nom', 'Alice');
    cliquer(f, 'button[type=submit]');
    await vi.waitFor(() => expect(appels.length).toBe(1));
    expect(JSON.parse(appels[0].corps!)).toMatchObject({ particulier: false, siren: '123456782', etrangere: false });
  });

  it('societe etrangere : envoie la TVA, pas de SIREN', async () => {
    const appels = simulerFetch({ 'POST /api/plugins/clients': { status: 201, corps: { issue: 'CREE', societeId: 57 } } });
    const f = monter();
    cliquer(f, 'input[value=societe]');
    cliquer(f, 'input.etrangere');
    saisir(f, '.tva', 'DE123456789');
    saisir(f, '.email', 'a@b.fr');
    saisir(f, '.nom', 'Alice');
    cliquer(f, 'button[type=submit]');
    await vi.waitFor(() => expect(appels.length).toBe(1));
    expect(JSON.parse(appels[0].corps!)).toMatchObject({ particulier: false, siren: null, tva: 'DE123456789', etrangere: true });
  });

  it('409 : affiche le message neutre du serveur, reste sur le formulaire', async () => {
    simulerFetch({
      'POST /api/plugins/clients': {
        status: 409,
        corps: { issue: 'DEJA_CONNUE', message: "société déjà connue, une demande d'accès a été envoyée à son manager" },
      },
    });
    const f = monter();
    cliquer(f, 'input[value=societe]');
    saisir(f, '.siren', '123456782');
    saisir(f, '.email', 'a@b.fr');
    saisir(f, '.nom', 'Alice');
    cliquer(f, 'button[type=submit]');
    await vi.waitFor(() => {
      f.detectChanges();
      expect(f.nativeElement.querySelector('[role=alert]')?.textContent).toContain('déjà connue');
    });
    expect(TestBed.inject(Navigation).chemin()).not.toMatch(/^\/clients\/[0-9]/);
  });

  it('403 : etat d erreur, aucune fiche ouverte', async () => {
    simulerFetch({ 'POST /api/plugins/clients': { status: 403 } });
    const f = monter();
    cliquer(f, 'input[value=particulier]');
    saisir(f, '.email', 'a@b.fr');
    saisir(f, '.nom', 'Alice');
    cliquer(f, 'button[type=submit]');
    await vi.waitFor(() => {
      f.detectChanges();
      expect(f.nativeElement.querySelector('[role=alert]')?.textContent).toContain('droit');
    });
  });
});
