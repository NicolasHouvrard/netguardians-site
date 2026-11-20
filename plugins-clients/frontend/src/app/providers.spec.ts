import { APP_ID } from '@angular/core';
import { createApplication } from '@angular/platform-browser';
import { PROVIDERS_PLUGIN } from './providers';

describe('PROVIDERS_PLUGIN', () => {
  it("utilise un APP_ID propre, distinct de celui du noyau ('ng')", async () => {
    const app = await createApplication({ providers: PROVIDERS_PLUGIN });
    expect(app.injector.get(APP_ID)).toBe('clients');
    app.destroy();
  });
});
