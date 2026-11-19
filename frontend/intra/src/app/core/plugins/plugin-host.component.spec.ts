import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, UrlSegment } from '@angular/router';
import { BehaviorSubject, of } from 'rxjs';
import { PluginHostComponent, pluginsMatcher } from './plugin-host.component';
import { PluginLoaderService } from './plugin-loader.service';
import { NavigationService, Tuile } from '../navigation/navigation.service';
import { AuthService } from '../auth/auth.service';

class ElementTest extends HTMLElement {
  chemin?: string;
  jeton?: string | null;
}
for (const nom of ['clients-app-v100', 'clients-app-v110']) {
  if (!customElements.get(nom)) customElements.define(nom, class extends ElementTest {});
}

const seg = (...p: string[]) => p.map(x => new UrlSegment(x, {}));

describe('PluginHostComponent', () => {
  let url$: BehaviorSubject<UrlSegment[]>;
  let version: string;
  let zone: string;
  const charger = vi.fn((plugin: string, v: string) =>
    Promise.resolve(`${plugin}-app-v${v.replace(/\./g, '')}`));

  function monter() {
    TestBed.configureTestingModule({
      imports: [PluginHostComponent],
      providers: [
        { provide: ActivatedRoute, useValue: { url: url$ } },
        { provide: PluginLoaderService, useValue: { charger } },
        { provide: AuthService, useValue: { token: () => 'JWT' } },
        {
          provide: NavigationService,
          useValue: {
            tuilesAutorisees: () =>
              of([{ plugin: 'clients', libelle: 'C', icone: 'x', chemin: '/', zone, version } as Tuile]),
          },
        },
      ],
    });
    const fixture = TestBed.createComponent(PluginHostComponent);
    fixture.detectChanges();
    return fixture;
  }

  const attendre = () => new Promise(r => setTimeout(r, 0));

  beforeEach(() => {
    zone = 'PARTENAIRE';
    charger.mockClear();
    version = '1.0.0';
    url$ = new BehaviorSubject<UrlSegment[]>(seg('plugins', 'clients', 'liste', '3'));
  });

  it('(d) crée l\'élément attendu et lui transmet chemin et jeton', async () => {
    const fixture = monter();
    await attendre();
    const el = fixture.nativeElement.querySelector('clients-app-v100') as ElementTest;
    expect(el).not.toBeNull();
    expect(el.chemin).toBe('/liste/3');
    expect(el.jeton).toBe('JWT');
    expect(charger).toHaveBeenCalledWith('clients', '1.0.0');
  });

  it('un changement de chemin met à jour le même élément', async () => {
    const fixture = monter();
    await attendre();
    const el = fixture.nativeElement.querySelector('clients-app-v100') as ElementTest;
    url$.next(seg('plugins', 'clients', 'autre'));
    await attendre();
    expect(fixture.nativeElement.querySelector('clients-app-v100')).toBe(el);
    expect(el.chemin).toBe('/autre');
  });

  it('recrée l\'élément quand la version du plugin change', async () => {
    const fixture = monter();
    await attendre();
    version = '1.1.0';
    url$.next(seg('plugins', 'clients', 'liste'));
    await attendre();
    expect(fixture.nativeElement.querySelector('clients-app-v100')).toBeNull();
    expect(fixture.nativeElement.querySelector('clients-app-v110')).not.toBeNull();
  });

  it('une navigation plus récente prend le relais d\'un chargement plus ancien en attente', async () => {
    version = '1.0.0';
    let liberer!: (nom: string) => void;
    charger.mockImplementationOnce(() => new Promise<string>(r => (liberer = r)));
    const fixture = monter(); // premier afficher() : bloqué sur le chargement de la 1.0.0
    await attendre();
    version = '1.1.0';
    url$.next(seg('plugins', 'clients', 'liste')); // second afficher() : 1.1.0, résolu tout de suite
    await attendre();
    expect(fixture.nativeElement.querySelector('clients-app-v110')).not.toBeNull();
    liberer('clients-app-v100'); // le plus ancien se termine en retard : ne doit rien ajouter
    await attendre();
    expect(fixture.nativeElement.querySelector('clients-app-v100')).toBeNull();
    expect(fixture.nativeElement.querySelector('clients-app-v110')).not.toBeNull();
  });

  it('plugin absent des tuiles : message, aucun élément', async () => {
    url$.next(seg('plugins', 'inconnu'));
    const fixture = monter();
    await attendre();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('introuvable');
    expect(charger).not.toHaveBeenCalled();
  });

  it('la zone de la tuile vient du serveur : une tuile de toute autre zone renvoyée est hébergée', async () => {
    zone = 'CLIENT';
    const fixture = monter();
    await attendre();
    expect(fixture.nativeElement.querySelector('clients-app-v100')).not.toBeNull();
  });

  it('pluginsMatcher consomme tout après /plugins/<plugin>', () => {
    const m = pluginsMatcher(seg('plugins', 'clients', 'a', 'b'));
    expect(m?.consumed).toHaveLength(4);
    expect(m?.posParams?.['plugin'].path).toBe('clients');
    expect(pluginsMatcher(seg('plugins'))).toBeNull();
    expect(pluginsMatcher(seg('admin', 'x'))).toBeNull();
  });
});
