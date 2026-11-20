import { createApplication } from '@angular/platform-browser';
import { createCustomElement } from '@angular/elements';
import { PROVIDERS_PLUGIN } from './app/providers';
import { ClientsAppComponent } from './app/clients-app.component';
import { NOM_ELEMENT } from './app/element-name.generated';

(async () => {
  const app = await createApplication({ providers: PROVIDERS_PLUGIN });
  if (!customElements.get(NOM_ELEMENT)) {
    customElements.define(NOM_ELEMENT, createCustomElement(ClientsAppComponent, { injector: app.injector }));
  }
})();
