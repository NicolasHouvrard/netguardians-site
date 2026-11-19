import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { ClientsAppComponent } from './clients-app.component';
import { simulerFetch } from './test-utils';

describe('ClientsAppComponent', () => {
  it('suit la propriete chemin et transmet le jeton sans l exposer dans le DOM', async () => {
    const r = { societeId: 11, siren: 'S11', tva: null, origine: 'M' };
    const appels = simulerFetch({
      'GET /api/plugins/clients/liste': { status: 200, corps: [] },
      'GET /api/plugins/clients/11/fiche': { status: 200, corps: { resume: r, partenaireSocieteId: 1, capacites: [] } },
      'GET /api/plugins/clients/11/blocs/resume': { status: 200, corps: r },
    });
    TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
    const f = TestBed.createComponent(ClientsAppComponent);
    f.componentRef.setInput('chemin', '/clients');
    f.componentRef.setInput('jeton', 'SECRET-JT');
    f.detectChanges();
    await vi.waitFor(() => expect(appels.some(a => a.url.endsWith('/liste'))).toBe(true));
    f.componentRef.setInput('chemin', '/clients/11');
    f.detectChanges();
    await vi.waitFor(() => {
      f.detectChanges();
      expect(f.nativeElement.shadowRoot.textContent).toContain('S11');
    });
    expect(f.nativeElement.shadowRoot.innerHTML).not.toContain('SECRET-JT');
    expect(appels.every(a => a.authorization === 'Bearer SECRET-JT')).toBe(true);
  });

  it('chemin inconnu : message', () => {
    TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
    const f = TestBed.createComponent(ClientsAppComponent);
    f.componentRef.setInput('chemin', '/n-importe-quoi');
    f.detectChanges();
    expect(f.nativeElement.shadowRoot.textContent).toContain('introuvable');
  });
});
