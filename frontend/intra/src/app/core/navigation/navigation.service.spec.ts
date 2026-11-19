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

  it('appelle /api/navigation SANS zone : le serveur la décide, chaque tuile porte la sienne', () => {
    let recu: { zone: string }[] = [];
    service.tuilesAutorisees().subscribe(t => (recu = t));
    const req = http.expectOne(r => r.url === '/api/navigation');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.has('zone')).toBe(false);
    expect(req.request.params.has('societeId')).toBe(false);
    req.flush([{ plugin: 'a', libelle: 'A', icone: 'x', chemin: '/a', zone: 'PARTENAIRE', version: '1.0.0' }]);
    expect(recu).toHaveLength(1);
    expect(recu[0].zone).toBe('PARTENAIRE');
  });

  it('transmet la société quand elle est donnée', () => {
    service.tuilesAutorisees(42).subscribe();
    const req = http.expectOne(r => r.url === '/api/navigation');
    expect(req.request.params.get('societeId')).toBe('42');
    req.flush([]);
  });

  it('lienPlugin place toujours le lien sous /plugins/<plugin>', () => {
    expect(lienPlugin('clients', '/liste/3')).toEqual(['/plugins', 'clients', 'liste', '3']);
    expect(lienPlugin('clients', '/')).toEqual(['/plugins', 'clients']);
  });
});
