import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { NavigationService, lienPlugin } from './navigation.service';
import { AuthService } from '../auth/auth.service';

describe('NavigationService', () => {
  let service: NavigationService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: AuthService, useValue: {} }],
    });
    service = TestBed.inject(NavigationService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('appelle /api/navigation avec la zone', () => {
    let recu: unknown;
    service.tuiles('PARTENAIRE').subscribe(t => (recu = t));
    const req = http.expectOne(r => r.url === '/api/navigation');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('zone')).toBe('PARTENAIRE');
    expect(req.request.params.has('societeId')).toBe(false);
    req.flush([{ plugin: 'a', libelle: 'A', icone: 'x', chemin: '/a', zone: 'PARTENAIRE', version: '1.0.0' }]);
    expect(recu).toHaveLength(1);
  });

  it('transmet la société quand elle est donnée', () => {
    service.tuiles('CLIENT', 42).subscribe();
    const req = http.expectOne(r => r.url === '/api/navigation');
    expect(req.request.params.get('societeId')).toBe('42');
    req.flush([]);
  });

  it('lienPlugin place toujours le lien sous /plugins/<plugin>', () => {
    expect(lienPlugin('clients', '/liste/3')).toEqual(['/plugins', 'clients', 'liste', '3']);
    expect(lienPlugin('clients', '/')).toEqual(['/plugins', 'clients']);
  });
});
