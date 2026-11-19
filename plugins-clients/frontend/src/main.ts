import { createApplication } from '@angular/platform-browser';
import { createCustomElement } from '@angular/elements';
import { provideZonelessChangeDetection } from '@angular/core';
import { ClientsAppComponent } from './app/clients-app.component';
import { NOM_ELEMENT } from './app/element-name.generated';

(async () => {
  const app = await createApplication({ providers: [provideZonelessChangeDetection()] });
  if (!customElements.get(NOM_ELEMENT)) {
    customElements.define(NOM_ELEMENT, createCustomElement(ClientsAppComponent, { injector: app.injector }));
  }
})();
