import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { PartenairesApi } from './partenaires-api';
import { PartenaireClientCreationComponent } from './partenaire-client-creation.component';
import { Navigation } from './navigation';
import { simulerFetch } from './test-utils';

type Fixture = { nativeElement: HTMLElement; detectChanges(): void };

function monter(partenaireId: string) {
  TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
  TestBed.inject(PartenairesApi).jeton.set('JT');
  TestBed.inject(Navigation).aller('/partenaires/' + partenaireId + '/clients/nouveau');
  const f = TestBed.createComponent(PartenaireClientCreationComponent);
  f.componentRef.setInput('partenaireId', partenaireId);
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
const URL = 'POST /api/plugins/partenaires/42/clients';

describe('PartenaireClientCreationComponent', () => {
  it('particulier : ni siren ni tva, jamais de partenaireSocieteId dans le corps', async () => {
    const appels = simulerFetch({ [URL]: { status: 201, corps: { issue: 'CREE', societeId: 5 } } });
    const f = monter('42');
    cliquer(f, 'input[value=particulier]');
    expect(f.nativeElement.querySelector('.siren')).toBeNull();
    saisir(f, '.email', 'a@b.fr');
    saisir(f, '.nom', 'Alice');
    cliquer(f, 'button[type=submit]');
    await vi.waitFor(() => expect(appels.length).toBe(1));
    expect(JSON.parse(appels[0].corps!)).toEqual({ particulier: true, siren: null, tva: null, email: 'a@b.fr', nom: 'Alice' });
    expect(appels[0].corps).not.toContain('partenaire');
    expect(appels[0].authorization).toBe('Bearer JT');
    await vi.waitFor(() => expect(TestBed.inject(Navigation).chemin()).toBe('/partenaires'));
  });

  it('societe : envoie SIREN et TVA', async () => {
    const appels = simulerFetch({ [URL]: { status: 201, corps: { issue: 'CREE', societeId: 5 } } });
    const f = monter('42');
    cliquer(f, 'input[value=societe]');
    saisir(f, '.siren', '123456782');
    saisir(f, '.tva', 'FR1');
    saisir(f, '.email', 'a@b.fr');
    saisir(f, '.nom', 'Alice');
    cliquer(f, 'button[type=submit]');
    await vi.waitFor(() => expect(appels.length).toBe(1));
    const corps = JSON.parse(appels[0].corps!);
    expect(corps).toEqual({ particulier: false, siren: '123456782', tva: 'FR1', email: 'a@b.fr', nom: 'Alice' });
    expect(Object.keys(corps)).not.toContain('partenaireSocieteId');
  });

  it('409 : message serveur verbatim, en texte', async () => {
    simulerFetch({ [URL]: { status: 409, corps: { issue: 'DEJA_CONNUE', message: '<b>déjà</b> connue' } } });
    const f = monter('42');
    cliquer(f, 'input[value=particulier]');
    saisir(f, '.email', 'a@b.fr');
    saisir(f, '.nom', 'Alice');
    cliquer(f, 'button[type=submit]');
    await vi.waitFor(() => {
      f.detectChanges();
      expect(f.nativeElement.querySelector('[role=alert]')?.textContent).toBe('<b>déjà</b> connue');
    });
    expect(f.nativeElement.querySelector('[role=alert] b')).toBeNull();
  });

  it('identifiant non numerique : etat d erreur, aucun appel reseau', async () => {
    const appels = simulerFetch({});
    const f = monter('abc/../x');
    cliquer(f, 'input[value=particulier]');
    saisir(f, '.email', 'a@b.fr');
    saisir(f, '.nom', 'Alice');
    cliquer(f, 'button[type=submit]');
    await vi.waitFor(() => {
      f.detectChanges();
      expect(f.nativeElement.querySelector('[role=alert]')).not.toBeNull();
    });
    expect(appels.length).toBe(0);
  });

  it('403 : acces reserve a l administrateur du serveur maitre', async () => {
    simulerFetch({ [URL]: { status: 403 } });
    const f = monter('42');
    cliquer(f, 'input[value=particulier]');
    saisir(f, '.email', 'a@b.fr');
    saisir(f, '.nom', 'Alice');
    cliquer(f, 'button[type=submit]');
    await vi.waitFor(() => {
      f.detectChanges();
      expect(f.nativeElement.querySelector('[role=alert]')?.textContent).toContain("Accès réservé à l'administrateur du serveur maître");
    });
  });
});
