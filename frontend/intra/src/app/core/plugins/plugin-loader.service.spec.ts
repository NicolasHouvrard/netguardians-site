import { TestBed } from '@angular/core/testing';
import { PluginLoaderService } from './plugin-loader.service';

function scripts(): HTMLScriptElement[] {
  return Array.from(document.head.querySelectorAll('script[type="module"]'));
}

describe('PluginLoaderService', () => {
  let service: PluginLoaderService;

  beforeEach(() => {
    scripts().forEach(s => s.remove());
    service = TestBed.inject(PluginLoaderService);
  });

  it('nomme l\'élément <plugin>-app-v<version, points remplacés par des tirets>', () => {
    expect(service.nomElement('clients', '1.2.3')).toBe('clients-app-v1-2-3');
    expect(service.nomElement('clients', '1.0.0-SNAPSHOT')).toBe('clients-app-v1-0-0_dsnapshot');
    expect(service.nomElement('clients', '1.0.0-beta.1')).toBe('clients-app-v1-0-0_dbeta-1');
    expect(service.nomElement('clients', '1.0.0+Build.5')).toBe('clients-app-v1-0-0_u2b_build-5');
  });

  it('le nom est injectif : deux versions différentes n\'ont jamais le même nom', () => {
    expect(service.nomElement('clients', '1.1.10')).not.toBe(service.nomElement('clients', '1.11.0'));
    expect(service.nomElement('clients', '11.1.0')).not.toBe(service.nomElement('clients', '1.11.0'));
    const versions = ['1.0.0', '1.0.1', '1.1.0', '1.10.0', '11.0.0', '1.1.10', '1.11.0', '1.0.0-1', '1.0.0-a',
      '1.0.0+a', '1.0.0-a.1', '1.0.0-a-1', '1.0.0_a', '1.0.0_da'];
    const noms = versions.map(v => service.nomElement('clients', v));
    expect(new Set(noms).size).toBe(versions.length);
    // nom d'élément personnalisé valide : minuscules, commence par une lettre, contient un tiret
    for (const nom of noms) expect(nom).toMatch(/^[a-z][a-z0-9_-]*-[a-z0-9_-]*$/);
  });

  it('n\'injecte pas deux fois le même script', () => {
    const a = service.charger('clients', '1.0.0');
    const b = service.charger('clients', '1.0.0');
    expect(a).toBe(b);
    expect(scripts()).toHaveLength(1);
    expect(scripts()[0].src).toContain('/api/plugins/clients/ui/1.0.0/main.js');
  });

  it('une nouvelle version injecte un nouveau script', () => {
    service.charger('clients', '1.0.0');
    service.charger('clients', '1.1.0');
    expect(scripts().map(s => s.getAttribute('src'))).toEqual([
      '/api/plugins/clients/ui/1.0.0/main.js',
      '/api/plugins/clients/ui/1.1.0/main.js',
    ]);
  });

  it('résout avec le nom de l\'élément au chargement du module', async () => {
    const p = service.charger('clients', '1.0.0');
    scripts()[0].dispatchEvent(new Event('load'));
    await expect(p).resolves.toBe('clients-app-v1-0-0');
  });

  it('une erreur de chargement rejette et autorise une nouvelle tentative', async () => {
    const p = service.charger('clients', '1.0.0');
    scripts()[0].dispatchEvent(new Event('error'));
    await expect(p).rejects.toThrow();
    expect(scripts()).toHaveLength(0);
    service.charger('clients', '1.0.0');
    expect(scripts()).toHaveLength(1);
  });
});
