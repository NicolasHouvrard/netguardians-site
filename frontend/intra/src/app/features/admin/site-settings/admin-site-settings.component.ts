import { Component, inject, signal, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule }     from '@angular/material/input';
import { MatButtonModule }    from '@angular/material/button';
import { MatIconModule }      from '@angular/material/icon';
import { MatDividerModule }   from '@angular/material/divider';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatDialog, MatDialogModule }     from '@angular/material/dialog';
import { SiteConfigService, SiteConfig } from '../../../core/site-config.service';
import { MediaPickerDialogComponent }    from '../media/media-picker-dialog.component';

@Component({
  selector: 'app-admin-site-settings',
  standalone: true,
  imports: [
    FormsModule,
    MatFormFieldModule, MatInputModule, MatButtonModule,
    MatIconModule, MatDividerModule, MatSnackBarModule, MatDialogModule,
  ],
  template: `
    <div class="settings-wrap">
      <h2 class="settings-title">
        <mat-icon>settings</mat-icon> Paramétrage du site
      </h2>

      @if (loading()) {
        <p>Chargement…</p>
      } @else {
        <form (ngSubmit)="save()" #f="ngForm">

          <section>
            <h3>Identité</h3>
            <div class="row">
              <mat-form-field>
                <mat-label>Nom du site</mat-label>
                <input matInput [(ngModel)]="form.siteName" name="siteName">
              </mat-form-field>
              <mat-form-field>
                <mat-label>URL du site</mat-label>
                <input matInput [(ngModel)]="form.siteUrl" name="siteUrl" placeholder="https://monsite.com">
              </mat-form-field>
            </div>
            <div class="row">
              <mat-form-field class="full">
                <mat-label>Slogan (tagline)</mat-label>
                <input matInput [(ngModel)]="form.tagline" name="tagline">
              </mat-form-field>
            </div>
            <div class="row">
              <div class="image-field">
                <span class="image-label">Favicon</span>
                <div class="image-preview">
                  @if (form.faviconUrl) {
                    <img [src]="form.faviconUrl" alt="favicon">
                  } @else {
                    <mat-icon class="placeholder-icon">image</mat-icon>
                  }
                </div>
                <div class="image-url">{{ form.faviconUrl || 'Aucun favicon sélectionné' }}</div>
                <button mat-stroked-button type="button" (click)="pickImage('faviconUrl')">
                  <mat-icon>photo_library</mat-icon> Parcourir
                </button>
              </div>
              <div class="image-field">
                <span class="image-label">Logo</span>
                <div class="image-preview logo">
                  @if (form.logoUrl) {
                    <img [src]="form.logoUrl" alt="logo">
                  } @else {
                    <mat-icon class="placeholder-icon">image</mat-icon>
                  }
                </div>
                <div class="image-url">{{ form.logoUrl || 'Aucun logo sélectionné' }}</div>
                <button mat-stroked-button type="button" (click)="pickImage('logoUrl')">
                  <mat-icon>photo_library</mat-icon> Parcourir
                </button>
              </div>
            </div>
          </section>

          <mat-divider />

          <section>
            <h3>Contact</h3>
            <div class="row">
              <mat-form-field>
                <mat-label>Email de contact</mat-label>
                <input matInput [(ngModel)]="form.contactEmail" name="contactEmail">
              </mat-form-field>
              <mat-form-field>
                <mat-label>Téléphone</mat-label>
                <input matInput [(ngModel)]="form.contactPhone" name="contactPhone">
              </mat-form-field>
            </div>
            <div class="row">
              <mat-form-field class="full">
                <mat-label>Adresse</mat-label>
                <textarea matInput [(ngModel)]="form.address" name="address" rows="2"></textarea>
              </mat-form-field>
            </div>
          </section>

          <mat-divider />

          <section>
            <h3>SEO</h3>
            <div class="row">
              <mat-form-field class="full">
                <mat-label>Méta description</mat-label>
                <textarea matInput [(ngModel)]="form.metaDescription" name="metaDescription" rows="2"></textarea>
              </mat-form-field>
            </div>
            <div class="row">
              <mat-form-field class="full">
                <mat-label>Méta mots-clés</mat-label>
                <input matInput [(ngModel)]="form.metaKeywords" name="metaKeywords">
              </mat-form-field>
            </div>
          </section>

          <mat-divider />

          <section>
            <h3>Réseaux sociaux</h3>
            <div class="row">
              <mat-form-field>
                <mat-label>Facebook</mat-label>
                <input matInput [(ngModel)]="form.socialFacebook" name="socialFacebook">
              </mat-form-field>
              <mat-form-field>
                <mat-label>LinkedIn</mat-label>
                <input matInput [(ngModel)]="form.socialLinkedin" name="socialLinkedin">
              </mat-form-field>
            </div>
            <div class="row">
              <mat-form-field>
                <mat-label>Instagram</mat-label>
                <input matInput [(ngModel)]="form.socialInstagram" name="socialInstagram">
              </mat-form-field>
              <mat-form-field>
                <mat-label>Twitter / X</mat-label>
                <input matInput [(ngModel)]="form.socialTwitter" name="socialTwitter">
              </mat-form-field>
            </div>
            <div class="row">
              <mat-form-field>
                <mat-label>YouTube</mat-label>
                <input matInput [(ngModel)]="form.socialYoutube" name="socialYoutube">
              </mat-form-field>
            </div>
          </section>

          <mat-divider />

          <section>
            <h3>Pied de page &amp; tracking</h3>
            <div class="row">
              <mat-form-field class="full">
                <mat-label>Texte pied de page</mat-label>
                <input matInput [(ngModel)]="form.footerText" name="footerText">
              </mat-form-field>
            </div>
            <div class="row">
              <mat-form-field>
                <mat-label>Google Analytics ID</mat-label>
                <input matInput [(ngModel)]="form.googleAnalyticsId" name="googleAnalyticsId" placeholder="G-XXXXXXXXXX">
              </mat-form-field>
            </div>
          </section>

          <div class="actions">
            <button mat-flat-button color="primary" type="submit" [disabled]="saving()">
              <mat-icon>save</mat-icon>
              {{ saving() ? 'Enregistrement…' : 'Enregistrer' }}
            </button>
          </div>

        </form>
      }
    </div>
  `,
  styles: [`
    .settings-wrap  { max-width: 900px; }
    .settings-title { display: flex; align-items: center; gap: 8px; margin-bottom: 24px;
                      font-size: 20px; font-weight: 500; }
    h3  { font-size: 14px; font-weight: 600; text-transform: uppercase;
          letter-spacing: .5px; color: #888; margin: 20px 0 12px; }
    .row { display: flex; gap: 16px; flex-wrap: wrap; }
    mat-form-field { flex: 1; min-width: 240px; }
    .full { flex-basis: 100%; }
    mat-divider { margin: 8px 0 4px; }
    .actions { margin-top: 28px; }
    .image-field {
      flex: 1; min-width: 200px;
      display: flex; flex-direction: column; gap: 8px;
    }
    .image-label { font-size: 12px; color: #666; }
    .image-preview {
      width: 80px; height: 80px; border: 1px solid #e0e0e0;
      border-radius: 6px; overflow: hidden; background: #fafafa;
      display: flex; align-items: center; justify-content: center;
      img { width: 100%; height: 100%; object-fit: contain; }
      &.logo { width: 160px; }
    }
    .placeholder-icon { color: #ccc; font-size: 36px; width: 36px; height: 36px; }
    .image-url {
      font-size: 11px; color: #999; word-break: break-all;
      max-width: 220px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
    }
  `],
})
export class AdminSiteSettingsComponent implements OnInit {
  private readonly siteConfig = inject(SiteConfigService);
  private readonly snack      = inject(MatSnackBar);
  private readonly dialog     = inject(MatDialog);

  readonly loading = signal(true);
  readonly saving  = signal(false);

  form: Partial<SiteConfig> = {};

  ngOnInit() {
    const current = this.siteConfig.config.value();
    if (current) {
      this.form = { ...current };
      this.loading.set(false);
    } else {
      const sub = this.siteConfig.config.isLoading;
      const interval = setInterval(() => {
        const v = this.siteConfig.config.value();
        if (v) { this.form = { ...v }; this.loading.set(false); clearInterval(interval); }
      }, 100);
    }
  }

  pickImage(field: 'faviconUrl' | 'logoUrl') {
    this.dialog.open(MediaPickerDialogComponent, { width: '720px' })
      .afterClosed().subscribe((url: string | undefined) => {
        if (url) this.form[field] = url;
      });
  }

  save() {
    this.saving.set(true);
    this.siteConfig.save(this.form).subscribe({
      next: (saved) => {
        this.form = { ...saved };
        this.saving.set(false);
        this.snack.open('Paramètres enregistrés', 'OK', { duration: 3000 });
        this.siteConfig.config.reload();
      },
      error: () => {
        this.saving.set(false);
        this.snack.open('Erreur lors de l\'enregistrement', 'Fermer', { duration: 4000 });
      },
    });
  }
}
