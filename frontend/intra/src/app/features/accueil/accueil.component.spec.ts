import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AccueilComponent } from './accueil.component';
import { NavigationService, Tuile, ZoneNavigation } from '../../core/navigation/navigation.service';

function tuile(plugin: string, zone: ZoneNavigation, chemin = '/liste'): Tuile {
  return { plugin, libelle: 'Libelle ' + plugin, icone: 'home', chemin, zone, version: '1.0.0' };
}

function monter(tuiles: Tuile[]) {
  TestBed.configureTestingModule({
    imports: [AccueilComponent],
    providers: [
      provideRouter([]),
      { provide: NavigationService, useValue: { tuilesAutorisees: () => of(tuiles) } },
    ],
  });
  const fixture = TestBed.createComponent(AccueilComponent);
  fixture.detectChanges();
  return fixture.nativeElement as HTMLElement;
}

describe('AccueilComponent', () => {
  it('rend une carte par tuile', () => {
    const el = monter([tuile('clients', 'PARTENAIRE'), tuile('devis', 'PARTENAIRE')]);
    const cartes = el.querySelectorAll('a.carte');
    expect(cartes).toHaveLength(2);
    expect(cartes[0].textContent).toContain('Libelle clients');
    expect(cartes[0].getAttribute('href')).toBe('/plugins/clients/liste');
  });

  it('sans tuile : « Aucune application disponible »', () => {
    const el = monter([]);
    expect(el.querySelectorAll('a.carte')).toHaveLength(0);
    expect(el.textContent).toContain('Aucune application disponible');
  });

  it('(e) affiche les tuiles telles que le serveur les renvoie, chacune avec sa zone', () => {
    const el = monter([tuile('suivi', 'CLIENT', '/suivi'), tuile('clients', 'PARTENAIRE', '/liste')]);
    const liens = Array.from(el.querySelectorAll('a')).map(a => a.getAttribute('href'));
    expect(liens).toEqual(['/plugins/suivi/suivi', '/plugins/clients/liste']);
  });

  it('rend une carte pour une tuile de la zone ADMIN, qui ouvre le plugin-host', () => {
    const el = monter([tuile('partenaires', 'ADMIN', '/partenaires')]);
    const cartes = el.querySelectorAll('a.carte');
    expect(cartes).toHaveLength(1);
    expect(cartes[0].textContent).toContain('Libelle partenaires');
    expect(cartes[0].getAttribute('href')).toBe('/plugins/partenaires/partenaires');
  });
});
