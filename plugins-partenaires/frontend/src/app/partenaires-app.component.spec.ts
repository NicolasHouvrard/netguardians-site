import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { PartenairesAppComponent } from './partenaires-app.component';
import { simulerFetch } from './test-utils';

function monter(chemin: string) {
  TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
  const f = TestBed.createComponent(PartenairesAppComponent);
  f.componentRef.setInput('chemin', chemin);
  f.componentRef.setInput('jeton', 'SECRET-JT');
  f.detectChanges();
  return f;
}

describe('PartenairesAppComponent', () => {
  it('aiguille selon chemin, transmet le jeton sans l exposer dans le DOM', async () => {
    const appels = simulerFetch({ 'GET /api/plugins/partenaires/liste': { status: 200, corps: [{ societeId: 3, siren: 'S3', raisonSociale: 'R3', etat: 'ACTIF' }] } });
    const f = monter('/partenaires');
    await vi.waitFor(() => {
      f.detectChanges();
      expect(f.nativeElement.shadowRoot.textContent).toContain('R3');
    });
    expect(f.nativeElement.shadowRoot.innerHTML).not.toContain('SECRET-JT');
    expect(appels.every(a => a.authorization === 'Bearer SECRET-JT')).toBe(true);
    f.componentRef.setInput('chemin', '/partenaires/nouveau');
    f.detectChanges();
    await vi.waitFor(() => {
      f.detectChanges();
      expect(f.nativeElement.shadowRoot.querySelector('form.partenaire')).not.toBeNull();
    });
  });

  it('chemin avec identifiant non numerique : page introuvable, aucun appel', () => {
    const appels = simulerFetch({});
    const f = monter('/partenaires/abc/clients/nouveau');
    expect(f.nativeElement.shadowRoot.textContent).toContain('introuvable');
    expect(appels.length).toBe(0);
  });

  it('chemin du modele de droits', async () => {
    simulerFetch({ 'GET /api/plugins/partenaires/modele-droits': { status: 200, corps: ['a.b'] } });
    const f = monter('/partenaires/modele');
    await vi.waitFor(() => {
      f.detectChanges();
      expect(f.nativeElement.shadowRoot.textContent).toContain('a.b');
    });
  });
});
