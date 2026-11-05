import { Component, inject, signal, OnInit } from '@angular/core';
import { FormBuilder, Validators, ReactiveFormsModule } from '@angular/forms';
import { Router, ActivatedRoute }        from '@angular/router';
import { MatCardModule }                 from '@angular/material/card';
import { MatFormFieldModule }            from '@angular/material/form-field';
import { MatInputModule }                from '@angular/material/input';
import { MatButtonModule }               from '@angular/material/button';
import { MatDividerModule }              from '@angular/material/divider';
import { MatProgressSpinnerModule }      from '@angular/material/progress-spinner';
import { MatIconModule }                 from '@angular/material/icon';
import { AuthService }                   from '../../core/auth/auth.service';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    MatCardModule, MatFormFieldModule, MatInputModule,
    MatButtonModule, MatDividerModule, MatProgressSpinnerModule, MatIconModule,
  ],
  templateUrl: './login.component.html',
  styleUrls:   ['./login.component.scss'],
})
export class LoginComponent implements OnInit {
  private readonly auth   = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route  = inject(ActivatedRoute);
  private readonly fb     = inject(FormBuilder);

  readonly loading    = signal(false);
  readonly oauthLoading = signal(false);
  readonly error      = signal<string | null>(null);
  readonly providers  = this.auth.providers;

  readonly form = this.fb.nonNullable.group({
    email:    ['', [Validators.required, Validators.email]],
    password: ['', Validators.required],
  });

  ngOnInit() {
    // Retour depuis OAuth2 : Spring Boot redirige vers /login?token=xxx
    const token = this.route.snapshot.queryParamMap.get('token');
    if (token) {
      this.oauthLoading.set(true);
      this.auth.loginWithOAuthToken(token).subscribe({
        next: () => this.router.navigate(['/']),
        error: () => {
          this.error.set('Échec de la connexion via le fournisseur externe.');
          this.oauthLoading.set(false);
        },
      });
    }
  }

  submit() {
    if (this.form.invalid) return;
    this.loading.set(true);
    this.error.set(null);
    const { email, password } = this.form.getRawValue();
    this.auth.login(email, password).subscribe({
      next: () => this.router.navigate(['/']),
      error: () => {
        this.error.set('Identifiants incorrects.');
        this.loading.set(false);
      },
    });
  }

  loginWith(providerId: string) {
    this.auth.loginWithProvider(providerId);
  }

  providerIcon(id: string): string {
    const icons: Record<string, string> = {
      google:    'icons/google.svg',
      microsoft: 'icons/microsoft.svg',
      azure:     'icons/microsoft.svg',
    };
    return icons[id] ?? '';
  }
}
