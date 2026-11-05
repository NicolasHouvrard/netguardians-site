import { Component, inject } from '@angular/core';
import { MatDialogRef, MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { ContactAccountComponent } from './contact-account.component';

export interface ContactAccountDialogData {
  customerId:   number;
  contactId:    number;
  contactName:  string;
  contactEmail: string | null;
  isEsn:        boolean;
}

@Component({
  selector: 'app-contact-account-dialog',
  standalone: true,
  imports: [MatDialogModule, MatButtonModule, ContactAccountComponent],
  template: `
    <h2 mat-dialog-title>
      <span>Compte — {{ data.contactName }}</span>
    </h2>
    <mat-dialog-content>
      <app-contact-account
        [customerId]="data.customerId"
        [contactId]="data.contactId"
        [contactEmail]="data.contactEmail"
        [isEsn]="data.isEsn" />
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button (click)="dialogRef.close()">Fermer</button>
    </mat-dialog-actions>
  `,
  styles: [`mat-dialog-content { min-width: 420px; padding-top: 8px; }`],
})
export class ContactAccountDialogComponent {
  readonly dialogRef = inject(MatDialogRef<ContactAccountDialogComponent>);
  readonly data: ContactAccountDialogData = inject(MAT_DIALOG_DATA);
}
