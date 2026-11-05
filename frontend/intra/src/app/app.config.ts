import { ApplicationConfig, APP_INITIALIZER, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter }          from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { firstValueFrom }        from 'rxjs';
import { routes }                from './app.routes';
import { jwtInterceptor }        from './core/auth/jwt.interceptor';
import { AuthService }           from './core/auth/auth.service';

async function initAuth(auth: AuthService): Promise<void> {
  if (auth.isLoggedIn()) return;
  // 1. Cookie _intra_jwt posé juste après le login Thymeleaf (valable 5 min)
  const cookieUser = await firstValueFrom(auth.tryJwtCookieLogin());
  if (cookieUser) return;
  // 2. Fallback : session Thymeleaf active (JSESSIONID) → /auth/session-token
  //    Couvre le cas où le cookie est absent/expiré mais la session est valide
  await firstValueFrom(auth.trySessionLogin());
}

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    provideHttpClient(withInterceptors([jwtInterceptor])),
    provideAnimationsAsync(),
    { provide: APP_INITIALIZER, useFactory: (auth: AuthService) => () => initAuth(auth), deps: [AuthService], multi: true },
  ]
};
