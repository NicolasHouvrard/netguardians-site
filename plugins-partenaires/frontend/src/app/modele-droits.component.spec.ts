import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { PartenairesApi } from './partenaires-api';
import { ModeleDroitsComponent } from './modele-droits.component';
import { simulerFetch } from './test-utils';

type Fixture = { nativeElement: HTMLElement; detectChanges(): void };
const URL = '/api/plugins/partenaires/modele-droits';

function monter() {
  TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
  TestBed.inject(PartenairesApi).jeton.set('JT');
  const f = TestBed.createComponent(ModeleDroitsComponent);
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
async function charge(f: Fixture, texte: string) {
  await vi.waitFor(() => {
    f.detectChanges();
    expect(f.nativeElement.textContent).toContain(texte);
  });
}

describe('ModeleDroitsComponent', () => {
  it('retire un droit puis enregistre l ensemble restant', async () => {
    const appels = simulerFetch({ ['GET ' + URL]: { status: 200, corps: ['clients.voir', 'clients.creer'] }, ['PUT ' + URL]: { status: 204 } });
    const f = monter();
    await charge(f, 'clients.creer');
    cliquer(f, 'button.retirer[data-droit="clients.voir"]');
    cliquer(f, 'button.enregistrer');
    await vi.waitFor(() => expect(appels.some(a => a.methode === 'PUT')).toBe(true));
    const put = appels.find(a => a.methode === 'PUT')!;
    expect(JSON.parse(put.corps!)).toEqual({ droits: ['clients.creer'] });
    expect(put.authorization).toBe('Bearer JT');
  });

  it('ajoute un droit valide', async () => {
    const appels = simulerFetch({ ['GET ' + URL]: { status: 200, corps: ['a.b'] }, ['PUT ' + URL]: { status: 204 } });
    const f = monter();
    await charge(f, 'a.b');
    saisir(f, '.nouveau', 'clients.voir_tout');
    cliquer(f, 'button.ajouter');
    expect(f.nativeElement.textContent).toContain('clients.voir_tout');
    cliquer(f, 'button.enregistrer');
    await vi.waitFor(() => expect(appels.some(a => a.methode === 'PUT')).toBe(true));
    expect(JSON.parse(appels.find(a => a.methode === 'PUT')!.corps!).droits.sort()).toEqual(['a.b', 'clients.voir_tout']);
  });

  it('refuse un droit invalide cote client', async () => {
    simulerFetch({ ['GET ' + URL]: { status: 200, corps: ['a.b'] } });
    const f = monter();
    await charge(f, 'a.b');
    for (const mauvais of ['', 'Majuscule', '1abc', 'a b', '<b>x</b>', 'a'.repeat(61)]) {
      saisir(f, '.nouveau', mauvais);
      cliquer(f, 'button.ajouter');
      expect(f.nativeElement.querySelector('[role=alert]')).not.toBeNull();
    }
    expect(f.nativeElement.querySelectorAll('li').length).toBe(1);
  });

  it('refuse d enregistrer une liste vide, sans appel', async () => {
    const appels = simulerFetch({ ['GET ' + URL]: { status: 200, corps: ['a.b'] } });
    const f = monter();
    await charge(f, 'a.b');
    cliquer(f, 'button.retirer');
    cliquer(f, 'button.enregistrer');
    expect(f.nativeElement.querySelector('[role=alert]')).not.toBeNull();
    expect(appels.some(a => a.methode === 'PUT')).toBe(false);
  });

  it('affiche les droits serveur comme du texte', async () => {
    simulerFetch({ ['GET ' + URL]: { status: 200, corps: ['<b>x</b>'] } });
    const f = monter();
    await charge(f, '<b>x</b>');
    expect(f.nativeElement.querySelector('li b')).toBeNull();
  });

  it('403 au chargement : acces reserve', async () => {
    simulerFetch({ ['GET ' + URL]: { status: 403 } });
    const f = monter();
    await charge(f, "Accès réservé à l'administrateur du serveur maître");
  });
});
