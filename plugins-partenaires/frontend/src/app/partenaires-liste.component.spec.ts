import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { PartenairesApi } from './partenaires-api';
import { PartenairesListeComponent } from './partenaires-liste.component';
import { Navigation } from './navigation';
import { simulerFetch } from './test-utils';

function monter() {
  TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
  TestBed.inject(PartenairesApi).jeton.set('JT');
  const f = TestBed.createComponent(PartenairesListeComponent);
  f.detectChanges();
  return f;
}

describe('PartenairesListeComponent', () => {
  it('affiche les partenaires et envoie le jeton dans l en-tete', async () => {
    const appels = simulerFetch({
      'GET /api/plugins/partenaires/liste': {
        status: 200,
        corps: [{ societeId: 7, siren: '123456782', raisonSociale: 'ACME', etat: 'ACTIF' }],
      },
    });
    const f = monter();
    await vi.waitFor(() => {
      f.detectChanges();
      expect(f.nativeElement.textContent).toContain('ACME');
    });
    const t = f.nativeElement.textContent;
    expect(t).toContain('123456782');
    expect(t).toContain('ACTIF');
    expect(t).toContain('7');
    expect(appels[0].authorization).toBe('Bearer JT');
    expect(appels[0].url).not.toContain('JT');
  });

  it('les boutons ouvrent les bons ecrans', async () => {
    simulerFetch({
      'GET /api/plugins/partenaires/liste': { status: 200, corps: [{ societeId: 7, siren: 'S', raisonSociale: 'A', etat: 'ACTIF' }] },
    });
    const f = monter();
    await vi.waitFor(() => {
      f.detectChanges();
      expect(f.nativeElement.querySelector('.creer-client')).not.toBeNull();
    });
    (f.nativeElement.querySelector('.creer-client') as HTMLElement).click();
    expect(TestBed.inject(Navigation).chemin()).toBe('/partenaires/7/clients/nouveau');
    (f.nativeElement.querySelector('.creer-partenaire') as HTMLElement).click();
    expect(TestBed.inject(Navigation).chemin()).toBe('/partenaires/nouveau');
    (f.nativeElement.querySelector('.modele') as HTMLElement).click();
    expect(TestBed.inject(Navigation).chemin()).toBe('/partenaires/modele');
  });

  it('affiche les donnees serveur comme du texte (jamais comme du HTML)', async () => {
    simulerFetch({
      'GET /api/plugins/partenaires/liste': {
        status: 200,
        corps: [{ societeId: 7, siren: '<b>x</b>', raisonSociale: '<b>gras</b><img src=x onerror=alert(1)>', etat: '<i>e</i>' }],
      },
    });
    const f = monter();
    await vi.waitFor(() => {
      f.detectChanges();
      expect(f.nativeElement.textContent).toContain('<b>gras</b>');
    });
    expect(f.nativeElement.querySelector('b')).toBeNull();
    expect(f.nativeElement.querySelector('img')).toBeNull();
    expect(f.nativeElement.querySelector('i')).toBeNull();
  });

  it('403 : acces reserve a l administrateur du serveur maitre', async () => {
    simulerFetch({ 'GET /api/plugins/partenaires/liste': { status: 403 } });
    const f = monter();
    await vi.waitFor(() => {
      f.detectChanges();
      expect(f.nativeElement.querySelector('[role=alert]')?.textContent).toContain("Accès réservé à l'administrateur du serveur maître");
    });
  });

  it('autre erreur : message generique', async () => {
    simulerFetch({ 'GET /api/plugins/partenaires/liste': { status: 500 } });
    const f = monter();
    await vi.waitFor(() => {
      f.detectChanges();
      expect(f.nativeElement.querySelector('[role=alert]')?.textContent).toContain('Une erreur est survenue');
    });
  });
});
