import { Component, inject, signal, OnInit } from '@angular/core';
import { FormBuilder, Validators, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { MatCardModule }            from '@angular/material/card';
import { MatFormFieldModule }        from '@angular/material/form-field';
import { MatInputModule }            from '@angular/material/input';
import { MatCheckboxModule }         from '@angular/material/checkbox';
import { MatButtonModule }           from '@angular/material/button';
import { MatIconModule }             from '@angular/material/icon';
import { MatProgressSpinnerModule }  from '@angular/material/progress-spinner';
import { MatDividerModule }          from '@angular/material/divider';
import { CustomerService }           from './customer.service';

@Component({
  selector: 'app-customer-form',
  standalone: true,
  templateUrl: './customer-form.component.html',
  styleUrl:    './customer-form.component.scss',
  imports: [
    ReactiveFormsModule, RouterLink,
    MatCardModule, MatFormFieldModule, MatInputModule,
    MatCheckboxModule, MatButtonModule, MatIconModule,
    MatProgressSpinnerModule, MatDividerModule,
  ],
})
export class CustomerFormComponent implements OnInit {
  private readonly svc    = inject(CustomerService);
  private readonly fb     = inject(FormBuilder);
  private readonly route  = inject(ActivatedRoute);
  private readonly router = inject(Router);

  readonly editId  = signal<number | null>(null);
  readonly loading = signal(false);
  readonly saving  = signal(false);
  readonly error   = signal<string | null>(null);

  readonly title = signal('Nouveau client');

  readonly form = this.fb.nonNullable.group({
    companyName: ['', Validators.required],
    contactName: [''],
    email:       ['', Validators.email],
    phone:       [''],
    website:     [''],
    address:     [''],
    siren:       ['', Validators.pattern(/^\d{9}$/)],
    vatNumber:   [''],
    active:      [true],
    isCustomer:  [true],
    isProspect:  [false],
    isSupplier:  [false],
  });

  ngOnInit() {
    const id = Number(this.route.snapshot.paramMap.get('id'));
    if (id) {
      this.editId.set(id);
      this.title.set('Modifier le client');
      this.loading.set(true);
      this.svc.getById(id).subscribe({
        next: c => {
          this.form.patchValue({
            companyName: c.companyName,
            contactName: c.contactName ?? '',
            email:       c.email       ?? '',
            phone:       c.phone       ?? '',
            website:     c.website     ?? '',
            address:     c.address     ?? '',
            siren:       c.siren       ?? '',
            vatNumber:   c.vatNumber   ?? '',
            active:      c.active,
            isCustomer:  c.isCustomer,
            isProspect:  c.isProspect,
            isSupplier:  c.isSupplier,
          });
          this.loading.set(false);
        },
        error: () => {
          this.error.set('Client introuvable.');
          this.loading.set(false);
        },
      });
    }
  }

  submit() {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    this.error.set(null);

    const req = this.form.getRawValue();
    const call = this.editId()
        ? this.svc.update(this.editId()!, req)
        : this.svc.create(req);

    call.subscribe({
      next: () => {
        this.svc.customers.reload();
        this.router.navigate(['/customers']);
      },
      error: () => {
        this.error.set('Une erreur est survenue. Veuillez réessayer.');
        this.saving.set(false);
      },
    });
  }
}
