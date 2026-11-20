import { APP_ID, EnvironmentProviders, Provider, provideZonelessChangeDetection } from '@angular/core';

/**
 * Fournisseurs de l'application du plugin. APP_ID propre : l'identifiant par defaut ('ng') est celui du noyau, dont
 * les elements <style ng-app-id="ng"> seraient sinon repris (et supprimes a la destruction) par le plugin.
 */
export const PROVIDERS_PLUGIN: (Provider | EnvironmentProviders)[] = [{ provide: APP_ID, useValue: 'clients' }, provideZonelessChangeDetection()];
