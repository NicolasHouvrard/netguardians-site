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

  it('nomme l\'élément <plugin>-app-v<version sans points>', () => {
    expect(service.nomElement('clients', '1.2.3')).toBe('clients-app-v123');
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
    await expect(p).resolves.toBe('clients-app-v100');
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
