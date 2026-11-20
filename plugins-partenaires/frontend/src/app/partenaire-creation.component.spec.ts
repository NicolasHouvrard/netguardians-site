import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { PartenairesApi } from './partenaires-api';
import { PartenaireCreationComponent } from './partenaire-creation.component';
import { Navigation } from './navigation';
import { simulerFetch } from './test-utils';

type Fixture = { nativeElement: HTMLElement; detectChanges(): void };

function monter() {
  TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
  TestBed.inject(PartenairesApi).jeton.set('JT');
  TestBed.inject(Navigation).aller('/partenaires/nouveau');
  const f = TestBed.createComponent(PartenaireCreationComponent);
  f.detectChanges();
  return f;
}
function saisir(f: Fixture, sel: string, v: string) {
  const i = f.nativeElement.querySelector(sel) as HTMLInputElement;
  i.value = v;
  i.dispatchEvent(new Event('input'));
  f.detectChanges();
}
function remplir(f: Fixture) {
  saisir(f, '.siren', '123456782');
  saisir(f, '.tva', 'FR12123456782');
  saisir(f, '.raison', 'ACME');
  saisir(f, '.email', 'a@b.fr');
  saisir(f, '.nom', 'Alice');
  (f.nativeElement.querySelector('button[type=submit]') as HTMLElement).click();
  f.detectChanges();
}

describe('PartenaireCreationComponent', () => {
  it('envoie les cinq champs, affiche un succes et retourne a la liste', async () => {
    const appels = simulerFetch({ 'POST /api/plugins/partenaires': { status: 201, corps: { societeId: 9 } } });
    const f = monter();
    remplir(f);
    await vi.waitFor(() => expect(appels.length).toBe(1));
    expect(JSON.parse(appels[0].corps!)).toEqual({
      siren: '123456782',
      tva: 'FR12123456782',
      raisonSociale: 'ACME',
      email: 'a@b.fr',
      nom: 'Alice',
    });
    expect(appels[0].authorization).toBe('Bearer JT');
    await vi.waitFor(() => expect(TestBed.inject(Navigation).chemin()).toBe('/partenaires'));
    expect(TestBed.inject(Navigation).message()).toContain('créé');
  });

  it('TVA facultative : envoyee a null si vide', async () => {
    const appels = simulerFetch({ 'POST /api/plugins/partenaires': { status: 201, corps: { societeId: 9 } } });
    const f = monter();
    saisir(f, '.siren', '123456782');
    saisir(f, '.raison', 'ACME');
    saisir(f, '.email', 'a@b.fr');
    saisir(f, '.nom', 'Alice');
    (f.nativeElement.querySelector('button[type=submit]') as HTMLElement).click();
    await vi.waitFor(() => expect(appels.length).toBe(1));
    expect(JSON.parse(appels[0].corps!).tva).toBeNull();
  });

  it('409 : message du serveur affiche tel quel, comme du texte', async () => {
    simulerFetch({ 'POST /api/plugins/partenaires': { status: 409, corps: { issue: 'SIREN_DEJA_CONNU', message: 'Ce SIREN <b>déjà</b> enregistré' } } });
    const f = monter();
    remplir(f);
    await vi.waitFor(() => {
      f.detectChanges();
      expect(f.nativeElement.querySelector('[role=alert]')?.textContent).toBe('Ce SIREN <b>déjà</b> enregistré');
    });
    expect(f.nativeElement.querySelector('[role=alert] b')).toBeNull();
    expect(TestBed.inject(Navigation).chemin()).not.toBe('/partenaires');
  });

  it('400 : message generique', async () => {
    simulerFetch({ 'POST /api/plugins/partenaires': { status: 400, corps: { message: 'secret serveur' } } });
    const f = monter();
    remplir(f);
    await vi.waitFor(() => {
      f.detectChanges();
      const t = f.nativeElement.querySelector('[role=alert]')?.textContent;
      expect(t).toContain('Données invalides');
      expect(t).not.toContain('secret');
    });
  });

  it('403 : acces reserve a l administrateur du serveur maitre', async () => {
    simulerFetch({ 'POST /api/plugins/partenaires': { status: 403 } });
    const f = monter();
    remplir(f);
    await vi.waitFor(() => {
      f.detectChanges();
      expect(f.nativeElement.querySelector('[role=alert]')?.textContent).toContain("Accès réservé à l'administrateur du serveur maître");
    });
  });
});
