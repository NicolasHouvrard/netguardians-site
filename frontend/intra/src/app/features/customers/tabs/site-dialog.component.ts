import { Component, inject } from '@angular/core';
import { FormBuilder, Validators, ReactiveFormsModule } from '@angular/forms';
import { MatDialogRef, MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule }     from '@angular/material/input';
import { MatCheckboxModule }  from '@angular/material/checkbox';
import { MatButtonModule }    from '@angular/material/button';
import { Site, SaveSiteRequest } from '../customer.model';

export interface SiteDialogData {
  site: Site | null;
}

@Component({
  selector: 'app-site-dialog',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    MatDialogModule, MatFormFieldModule, MatInputModule,
    MatCheckboxModule, MatButtonModule,
  ],
  template: `
    <h2 mat-dialog-title>{{ data.site ? 'Modifier le site' : 'Nouveau site' }}</h2>
    <mat-dialog-content>
      <form [formGroup]="form" class="dialog-form">
        <mat-form-field appearance="outline" class="full">
          <mat-label>Nom du site *</mat-label>
          <input matInput formControlName="name">
        </mat-form-field>
        <mat-form-field appearance="outline" class="full">
          <mat-label>Adresse</mat-label>
          <input matInput formControlName="address">
        </mat-form-field>
        <div class="row-2">
          <mat-form-field appearance="outline">
            <mat-label>Code postal</mat-label>
            <input matInput formControlName="zipCode">
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Ville</mat-label>
            <input matInput formControlName="city">
          </mat-form-field>
        </div>
        <mat-form-field appearance="outline" class="full">
          <mat-label>Pays</mat-label>
          <input matInput formControlName="country">
        </mat-form-field>
        <mat-checkbox formControlName="active">Site actif</mat-checkbox>
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button (click)="dialogRef.close()">Annuler</button>
      <button mat-flat-button color="primary" [disabled]="form.invalid" (click)="save()">
        Enregistrer
      </button>
    </mat-dialog-actions>
  `,
  styles: [`
    .dialog-form { display: flex; flex-direction: column; gap: 4px; min-width: 380px; padding-top: 8px; }
    .full { width: 100%; }
    .row-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
  `],
})
export class SiteDialogComponent {
  readonly dialogRef = inject(MatDialogRef<SiteDialogComponent>);
  readonly data: SiteDialogData = inject(MAT_DIALOG_DATA);
  private readonly fb = inject(FormBuilder);

  readonly form = this.fb.nonNullable.group({
    name:    [this.data.site?.name    ?? '', Validators.required],
    address: [this.data.site?.address ?? ''],
    city:    [this.data.site?.city    ?? ''],
    zipCode: [this.data.site?.zipCode ?? ''],
    country: [this.data.site?.country ?? 'France'],
    active:  [this.data.site?.active  ?? true],
  });

  save() {
    if (this.form.invalid) return;
    this.dialogRef.close(this.form.getRawValue() as SaveSiteRequest);
  }
}
