package com.logicielpourtous.site.features.mail;

public enum MailTemplateType {

    BOOKING_CONFIRMATION(
            "Confirmation de réservation",
            "{{clientName}}, {{bookingTitle}}, {{date}}, {{time}}, {{duration}}, {{type}}, {{assignedTo}}, {{meetLink}}, {{cancelUrl}}"
    ),
    BOOKING_CANCELLATION(
            "Annulation de réservation",
            "{{clientName}}, {{bookingTitle}}, {{date}}, {{time}}, {{newBookingUrl}}"
    ),
    TICKET_CREATED(
            "Nouveau ticket créé",
            "{{ticketId}}, {{subject}}, {{description}}, {{trackUrl}}"
    ),
    TICKET_REPLY(
            "Réponse à un ticket",
            "{{ticketId}}, {{subject}}, {{replyBody}}, {{trackUrl}}"
    ),
    INVOICE_SENT(
            "Facture envoyée",
            "{{invoiceNumber}}, {{amount}}, {{dueDate}}, {{downloadUrl}}"
    ),
    QUOTE_SENT(
            "Devis envoyé",
            "{{quoteNumber}}, {{amount}}, {{validUntil}}, {{downloadUrl}}"
    ),
    PASSWORD_RESET(
            "Réinitialisation du mot de passe",
            "{{fullName}}, {{resetUrl}}, {{expiresIn}}"
    ),
    REGISTRATION_CONFIRMATION(
            "Validation de l'adresse email",
            "{{fullName}}, {{companyName}}, {{verifyUrl}}"
    );

    public final String label;
    public final String variables;

    MailTemplateType(String label, String variables) {
        this.label     = label;
        this.variables = variables;
    }
}
