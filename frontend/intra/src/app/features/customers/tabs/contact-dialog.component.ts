import { Component, inject } from '@angular/core';
import { FormBuilder, Validators, ReactiveFormsModule } from '@angular/forms';
import { MatDialogRef, MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule }     from '@angular/material/input';
import { MatSelectModule }    from '@angular/material/select';
import { MatButtonModule }    from '@angular/material/button';
import { Contact, Site, SaveContactRequest } from '../customer.model';

export interface ContactDialogData {
  contact: Contact | null;
  sites: Site[];
}

@Component({
  selector: 'app-contact-dialog',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    MatDialogModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatButtonModule,
  ],
  template: `
    <h2 mat-dialog-title>{{ data.contact ? 'Modifier le contact' : 'Nouveau contact' }}</h2>
    <mat-dialog-content>
      <form [formGroup]="form" class="dialog-form">
        <div class="row-2">
          <mat-form-field appearance="outline">
            <mat-label>Prénom</mat-label>
            <input matInput formControlName="firstName">
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Nom *</mat-label>
            <input matInput formControlName="lastName">
          </mat-form-field>
        </div>
        <mat-form-field appearance="outline" class="full">
          <mat-label>Fonction</mat-label>
          <input matInput formControlName="position">
        </mat-form-field>
        <mat-form-field appearance="outline" class="full">
          <mat-label>Email</mat-label>
          <input matInput type="email" formControlName="email">
        </mat-form-field>
        <mat-form-field appearance="outline" class="full">
          <mat-label>Téléphone</mat-label>
          <input matInput formControlName="phone">
        </mat-form-field>
        <mat-form-field appearance="outline" class="full">
          <mat-label>Site</mat-label>
          <mat-select formControlName="siteId">
            <mat-option [value]="null">— Aucun —</mat-option>
            @for (s of data.sites; track s.id) {
              <mat-option [value]="s.id">{{ s.name }}</mat-option>
            }
          </mat-select>
        </mat-form-field>
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
    .dialog-form { display: flex; flex-direction: column; gap: 4px; min-width: 400px; padding-top: 8px; }
    .full { width: 100%; }
    .row-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
  `],
})
export class ContactDialogComponent {
  readonly dialogRef = inject(MatDialogRef<ContactDialogComponent>);
  readonly data: ContactDialogData = inject(MAT_DIALOG_DATA);
  private readonly fb = inject(FormBuilder);

  readonly form = this.fb.nonNullable.group({
    firstName: [this.data.contact?.firstName ?? ''],
    lastName:  [this.data.contact?.lastName  ?? '', Validators.required],
    position:  [this.data.contact?.position  ?? ''],
    email:     [this.data.contact?.email     ?? '', Validators.email],
    phone:     [this.data.contact?.phone     ?? ''],
    siteId:    [this.data.contact?.siteId    ?? null],
  });

  save() {
    if (this.form.invalid) return;
    this.dialogRef.close(this.form.getRawValue() as SaveContactRequest);
  }
}
