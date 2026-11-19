import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AccueilComponent } from './accueil.component';
import { NavigationService, Tuile, ZoneNavigation } from '../../core/navigation/navigation.service';

function tuile(plugin: string, zone: ZoneNavigation, chemin = '/liste'): Tuile {
  return { plugin, libelle: 'Libelle ' + plugin, icone: 'home', chemin, zone, version: '1.0.0' };
}

function monter(zone: ZoneNavigation, tuiles: Tuile[]) {
  TestBed.configureTestingModule({
    imports: [AccueilComponent],
    providers: [
      provideRouter([]),
      { provide: NavigationService, useValue: { zoneCourante: () => zone, tuiles: () => of(tuiles) } },
    ],
  });
  const fixture = TestBed.createComponent(AccueilComponent);
  fixture.detectChanges();
  return fixture.nativeElement as HTMLElement;
}

describe('AccueilComponent', () => {
  it('rend une carte par tuile', () => {
    const el = monter('PARTENAIRE', [tuile('clients', 'PARTENAIRE'), tuile('devis', 'PARTENAIRE')]);
    const cartes = el.querySelectorAll('a.carte');
    expect(cartes).toHaveLength(2);
    expect(cartes[0].textContent).toContain('Libelle clients');
    expect(cartes[0].getAttribute('href')).toBe('/plugins/clients/liste');
  });

  it('sans tuile : « Aucune application disponible »', () => {
    const el = monter('PARTENAIRE', []);
    expect(el.querySelectorAll('a.carte')).toHaveLength(0);
    expect(el.textContent).toContain('Aucune application disponible');
  });

  it('(e) en espace client, aucun lien vers une route de la zone PARTENAIRE', () => {
    const el = monter('CLIENT', [tuile('suivi', 'CLIENT', '/suivi'), tuile('clients', 'PARTENAIRE', '/liste')]);
    const liens = Array.from(el.querySelectorAll('a')).map(a => a.getAttribute('href'));
    expect(liens).toEqual(['/plugins/suivi/suivi']);
    expect(el.innerHTML).not.toContain('clients');
  });
});
