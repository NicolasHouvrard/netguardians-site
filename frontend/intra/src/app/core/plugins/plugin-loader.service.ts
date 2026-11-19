import { Injectable, inject } from '@angular/core';
import { DOCUMENT } from '@angular/common';

/**
 * Charge le front d'un plugin : un module ES (`ui/main.js` côté serveur, URL versionnée) qui déclare un élément
 * personnalisé. Un module déjà chargé ne se décharge pas : chaque version a son propre script ET son propre nom
 * d'élément (`<plugin>-app-v<version sans points>`).
 */
@Injectable({ providedIn: 'root' })
export class PluginLoaderService {
  private readonly document = inject(DOCUMENT);
  private readonly charges = new Map<string, Promise<string>>();

  /** Nom de l'élément personnalisé de cette version du plugin. */
  nomElement(plugin: string, version: string): string {
    const suffixe = version.toLowerCase().replace(/\./g, '').replace(/[^a-z0-9-]/g, '-');
    return `${plugin}-app-v${suffixe}`;
  }

  /** Injecte une seule fois le script de cette version ; résout avec le nom de l'élément une fois le module chargé. */
  charger(plugin: string, version: string): Promise<string> {
    const cle = `${plugin}@${version}`;
    const existant = this.charges.get(cle);
    if (existant) return existant;

    const nom = this.nomElement(plugin, version);
    const promesse = new Promise<string>((resolve, reject) => {
      const script = this.document.createElement('script');
      script.type = 'module';
      script.src = `/api/plugins/${encodeURIComponent(plugin)}/ui/${encodeURIComponent(version)}/main.js`;
      script.addEventListener('load', () => resolve(nom));
      script.addEventListener('error', () => {
        script.remove();
        this.charges.delete(cle); // permet une nouvelle tentative
        reject(new Error(`Chargement impossible de l'application ${plugin} ${version}.`));
      });
      this.document.head.appendChild(script);
    });
    this.charges.set(cle, promesse);
    return promesse;
  }
}
