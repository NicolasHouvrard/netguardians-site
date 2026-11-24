import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { ClientsApi } from './clients-api';
import { ClientsFicheComponent } from './clients-fiche.component';
import { simulerFetch } from './test-utils';

const RESUME = { societeId: 11, nom: 'Jeanne <b>Martin</b>', siren: '123456782', tva: null, origine: 'MANUEL' };

function monter(id: number) {
  TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
  TestBed.inject(ClientsApi).jeton.set('JT');
  const f = TestBed.createComponent(ClientsFicheComponent);
  f.componentRef.setInput('societeId', id);
  f.detectChanges();
  return f;
}
const texte = (f: { nativeElement: HTMLElement }) => f.nativeElement.textContent ?? '';

describe('ClientsFicheComponent', () => {
  it('affiche la fiche et le bloc resume', async () => {
    const appels = simulerFetch({
      'GET /api/plugins/clients/11/fiche': { status: 200, corps: { resume: RESUME, partenaireSocieteId: 1, capacites: ['CLIENT'] } },
      'GET /api/plugins/clients/11/blocs/resume': { status: 200, corps: RESUME },
    });
    const f = monter(11);
    await vi.waitFor(() => {
      f.detectChanges();
      expect(texte(f)).toContain('Résumé');
      expect(texte(f)).toContain('123456782');
    });
    expect(texte(f)).toContain('Jeanne <b>Martin</b>'); // nom affiche tel quel, echappe
    expect(f.nativeElement.querySelector('b')).toBeNull();
    expect(texte(f)).toContain('CLIENT');
    expect(appels.every(a => a.authorization === 'Bearer JT')).toBe(true);
  });

  it('404 : client introuvable, sans detail', async () => {
    simulerFetch({
      'GET /api/plugins/clients/11/fiche': { status: 404 },
      'GET /api/plugins/clients/11/blocs/resume': { status: 404 },
    });
    const f = monter(11);
    await vi.waitFor(() => {
      f.detectChanges();
      expect(f.nativeElement.querySelector('[role=alert]')?.textContent).toContain('introuvable');
    });
  });

  it('403 : etat d erreur', async () => {
    simulerFetch({
      'GET /api/plugins/clients/11/fiche': { status: 403 },
      'GET /api/plugins/clients/11/blocs/resume': { status: 403 },
    });
    const f = monter(11);
    await vi.waitFor(() => {
      f.detectChanges();
      expect(f.nativeElement.querySelector('[role=alert]')?.textContent).toContain('droit');
    });
  });
});
