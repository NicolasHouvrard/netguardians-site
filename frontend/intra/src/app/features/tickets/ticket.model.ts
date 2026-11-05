export interface TicketComment {
  id:          number;
  content:     string;
  isPublic:    boolean;
  authorName:  string | null;
  createdAt:   string;
  sourceEmail: string | null;
}

export interface TicketAttachment {
  id:             number;
  url:            string;
  originalName:   string;
  contentType:    string | null;
  displaySize:    string;
  image:          boolean;
  uploadedByName: string | null;
  uploadedAt:     string;
}

export interface TicketIntervention {
  id:             number;
  reference:      string | null;
  title:          string;
  status:         string | null;
  statusLabel:    string | null;
  scheduledStart: string | null;
}

export interface Ticket {
  id:             number;
  title:          string;
  description:    string | null;
  status:         string;
  statusLabel:    string;
  priority:       string | null;
  priorityLabel:  string | null;
  type:           string | null;
  typeLabel:      string | null;
  createdAt:      string;
  senderEmail:    string | null;
  // Client
  customerName:   string | null;
  customerId:     number | null;
  // Contact
  contactName:    string | null;
  contactId:      number | null;
  contactEmail:   string | null;
  // Site
  siteName:       string | null;
  siteId:         number | null;
  // Assignation
  assignedToName: string | null;
  assignedToId:   number | null;
  teamName:       string | null;
  teamId:         number | null;
  // Timeline
  comments:       TicketComment[];
  // Pièces jointes
  attachments:    TicketAttachment[];
  // Interventions liées
  interventions:  TicketIntervention[];
}

export interface TicketPage {
  content:       Ticket[];
  page:          number;
  size:          number;
  totalElements: number;
  totalPages:    number;
}

export interface TicketMeta {
  statuses:   { value: string; label: string }[];
  priorities: { value: string; label: string }[];
  types:      { value: string; label: string }[];
}

export interface TicketFormMeta extends TicketMeta {
  customers: { id: number; name: string }[];
  teams:     { id: number; name: string; memberIds: number[] }[];
  users:     { id: number; fullName: string }[];
}

export interface CreateTicketRequest {
  title:         string;
  description?:  string;
  priority?:     string;
  type?:         string;
  customerId?:   number;
  contactId?:    number;
  siteId?:       number;
  assignedToId?: number;
  teamId?:       number;
}
