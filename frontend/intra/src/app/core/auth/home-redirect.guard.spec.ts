import { TestBed } from '@angular/core/testing';
import { Router, UrlTree, provideRouter } from '@angular/router';
import { Observable, firstValueFrom, of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { homeRedirectGuard } from './home-redirect.guard';
import { AuthService } from './auth.service';
import { NavigationService, Tuile } from '../navigation/navigation.service';

describe('homeRedirectGuard', () => {
  const tuile: Tuile = { plugin: 'clients', libelle: 'Clients', icone: 'x', chemin: '/clients', zone: 'PARTENAIRE', version: '1.0.0' };
  let connecte = true;
  let esn = false;
  let portail = false;
  let tuiles$: Observable<Tuile[]>;

  async function cible(): Promise<string> {
    const router = TestBed.inject(Router);
    const res = TestBed.runInInjectionContext(() => homeRedirectGuard({} as never, {} as never));
    const arbre = (res instanceof Observable ? await firstValueFrom(res) : await Promise.resolve(res)) as UrlTree;
    return router.serializeUrl(arbre);
  }

  beforeEach(() => {
    connecte = true;
    esn = false;
    portail = false;
    tuiles$ = of([]);
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: { isLoggedIn: () => connecte, isEsnUser: () => esn, isPortalUser: () => portail } },
        { provide: NavigationService, useValue: { zoneCourante: () => 'PARTENAIRE', tuiles: () => tuiles$, tuilesAutorisees: () => tuiles$ } },
      ],
    });
  });

  it('non connecté -> /login sans appeler la navigation', async () => {
    connecte = false;
    tuiles$ = throwError(() => new Error('ne doit pas être appelé'));
    expect(await cible()).toBe('/login');
  });

  it('avec des tuiles -> /accueil', async () => {
    tuiles$ = of([tuile]);
    expect(await cible()).toBe('/accueil');
  });

  it('sans tuile -> ancienne cible : ESN vers /general', async () => {
    esn = true;
    expect(await cible()).toBe('/general');
  });

  it('sans tuile -> ancienne cible : compte portail vers /portail', async () => {
    portail = true;
    expect(await cible()).toBe('/portail');
  });

  it('sans tuile -> ancienne cible : ESN ET portail vers /general, sinon /general par défaut', async () => {
    esn = true;
    portail = true;
    expect(await cible()).toBe('/general');
    esn = false;
    portail = false;
    expect(await cible()).toBe('/general');
  });

  it('plateforme inactive (404) -> ancienne cible, jamais une page d\'erreur', async () => {
    portail = true;
    tuiles$ = throwError(() => new HttpErrorResponse({ status: 404 }));
    expect(await cible()).toBe('/portail');
    portail = false;
    esn = true;
    expect(await cible()).toBe('/general');
  });
});
