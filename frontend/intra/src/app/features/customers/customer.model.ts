export interface Customer {
  id: number;
  companyName: string;
  contactName: string | null;
  email: string | null;
  phone: string | null;
  website: string | null;
  address: string | null;
  siren: string | null;
  vatNumber: string | null;
  active: boolean;
  isCustomer: boolean;
  isSupplier: boolean;
  isProspect: boolean;
  isEsn: boolean;
  prospectStatus: string | null;
}

export interface CustomerPage {
  content: Customer[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface Site {
  id: number;
  name: string;
  address: string | null;
  city: string | null;
  zipCode: string | null;
  country: string | null;
  active: boolean;
  contactCount: number;
}

export interface Contact {
  id: number;
  firstName: string | null;
  lastName: string | null;
  fullName: string;
  email: string | null;
  phone: string | null;
  position: string | null;
  siteId: number | null;
  siteName: string | null;
  userId: number | null;
}

export interface ContactAccount {
  userId: number;
  active: boolean;
  emailVerified: boolean;
  mustChangePassword: boolean;
  roles: string[];
}

export const ESN_ROLES = [
  { name: 'USER',        label: 'Utilisateur' },
  { name: 'COMMERCIAL',  label: 'Commercial' },
  { name: 'TECHNICIEN',  label: 'Technicien' },
  { name: 'MARKETING',   label: 'Marketing' },
  { name: 'COMPTABLE',   label: 'Comptable' },
  { name: 'ADMIN',       label: 'Administrateur' },
];

export interface Subscription {
  id: number;
  name: string;
  billingModel: string | null;
  billingModelLabel: string | null;
  period: string | null;
  startDate: string;
  endDate: string | null;
  active: boolean;
  pricePerPeriod: number | null;
  unitCount: number | null;
  unitPrice: number | null;
  unitLabel: string;
  includedMinutes: number | null;
  formattedIncluded: string;
  formattedSpent: string;
  progressPercent: number;
}

export interface SaveSiteRequest {
  name: string;
  address?: string;
  city?: string;
  zipCode?: string;
  country?: string;
  active: boolean;
}

export interface SaveContactRequest {
  firstName?: string;
  lastName?: string;
  email?: string;
  phone?: string;
  position?: string;
  siteId?: number | null;
}

export interface SaveCustomerRequest {
  companyName: string;
  contactName?: string;
  email?: string;
  phone?: string;
  website?: string;
  address?: string;
  siren?: string;
  vatNumber?: string;
  active: boolean;
  isCustomer: boolean;
  isProspect: boolean;
  isSupplier: boolean;
}
