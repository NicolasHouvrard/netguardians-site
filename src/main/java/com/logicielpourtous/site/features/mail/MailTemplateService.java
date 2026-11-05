package com.logicielpourtous.site.features.mail;

import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class MailTemplateService {

    private final MailTemplateRepository templateRepository;

    public MailTemplateService(MailTemplateRepository templateRepository) {
        this.templateRepository = templateRepository;
    }

    public record RenderedMail(String subject, String html) {}

    public RenderedMail render(MailTemplateType type, Map<String, String> vars) {
        MailTemplate tpl = templateRepository.findByType(type)
                .orElseGet(() -> buildDefault(type));
        return new RenderedMail(
                replaceVars(tpl.getSubject(), vars),
                replaceVars(tpl.getHtmlBody(), vars)
        );
    }

    public MailTemplate getOrCreateDefault(MailTemplateType type) {
        return templateRepository.findByType(type)
                .orElseGet(() -> {
                    MailTemplate tpl = buildDefault(type);
                    return templateRepository.save(tpl);
                });
    }

    private String replaceVars(String text, Map<String, String> vars) {
        if (text == null) return "";
        for (Map.Entry<String, String> e : vars.entrySet()) {
            text = text.replace("{{" + e.getKey() + "}}", e.getValue() != null ? e.getValue() : "");
        }
        return text;
    }

    // ── Modèles par défaut ────────────────────────────────────────────────────

    private MailTemplate buildDefault(MailTemplateType type) {
        MailTemplate tpl = new MailTemplate();
        tpl.setType(type);
        return switch (type) {
            case BOOKING_CONFIRMATION -> {
                tpl.setSubject("✅ Confirmation : {{bookingTitle}}");
                tpl.setHtmlBody(BOOKING_CONFIRMATION_HTML);
                yield tpl;
            }
            case BOOKING_CANCELLATION -> {
                tpl.setSubject("❌ Annulation : {{bookingTitle}}");
                tpl.setHtmlBody(BOOKING_CANCELLATION_HTML);
                yield tpl;
            }
            case TICKET_CREATED -> {
                tpl.setSubject("🎫 Votre demande #{{ticketId}} a bien été reçue");
                tpl.setHtmlBody(TICKET_CREATED_HTML);
                yield tpl;
            }
            case TICKET_REPLY -> {
                tpl.setSubject("💬 Réponse à votre demande #{{ticketId}}");
                tpl.setHtmlBody(TICKET_REPLY_HTML);
                yield tpl;
            }
            case INVOICE_SENT -> {
                tpl.setSubject("🧾 Votre facture n°{{invoiceNumber}}");
                tpl.setHtmlBody(INVOICE_SENT_HTML);
                yield tpl;
            }
            case QUOTE_SENT -> {
                tpl.setSubject("📄 Votre devis n°{{quoteNumber}}");
                tpl.setHtmlBody(QUOTE_SENT_HTML);
                yield tpl;
            }
            case PASSWORD_RESET -> {
                tpl.setSubject("🔐 Réinitialisation de votre mot de passe");
                tpl.setHtmlBody(PASSWORD_RESET_HTML);
                yield tpl;
            }
            case REGISTRATION_CONFIRMATION -> {
                tpl.setSubject("✉️ Confirmez votre adresse email");
                tpl.setHtmlBody(REGISTRATION_CONFIRMATION_HTML);
                yield tpl;
            }
        };
    }

    // ── Templates HTML par défaut ─────────────────────────────────────────────

    private static final String BOOKING_CONFIRMATION_HTML = """
        <!DOCTYPE html>
        <html><body style="margin:0;padding:0;background:#f8fafc;font-family:'Segoe UI',Arial,sans-serif">
        <table width="100%" cellpadding="0" cellspacing="0" style="background:#f8fafc;padding:32px 0">
        <tr><td align="center">
        <table width="600" cellpadding="0" cellspacing="0" style="background:#fff;border-radius:12px;box-shadow:0 2px 8px rgba(0,0,0,.06);overflow:hidden">
          <tr><td style="background:#6366f1;padding:28px 32px">
            <h1 style="margin:0;color:#fff;font-size:22px;font-weight:700">✅ Rendez-vous confirmé</h1>
          </td></tr>
          <tr><td style="padding:32px">
            <p style="margin:0 0 16px;color:#374151;font-size:16px">Bonjour <strong>{{clientName}}</strong>,</p>
            <p style="margin:0 0 24px;color:#374151">Votre rendez-vous <strong>{{bookingTitle}}</strong> a bien été enregistré.</p>
            <table width="100%" cellpadding="0" cellspacing="0" style="background:#f8fafc;border-radius:8px;padding:4px 0;margin-bottom:24px">
              <tr><td style="padding:10px 16px;color:#6b7280;font-size:14px;width:130px">📅 Date</td>
                  <td style="padding:10px 16px;font-weight:600;color:#111827">{{date}}</td></tr>
              <tr style="background:#fff"><td style="padding:10px 16px;color:#6b7280;font-size:14px">🕐 Heure</td>
                  <td style="padding:10px 16px;font-weight:600;color:#111827">{{time}}</td></tr>
              <tr><td style="padding:10px 16px;color:#6b7280;font-size:14px">⏱ Durée</td>
                  <td style="padding:10px 16px;color:#374151">{{duration}}</td></tr>
              <tr style="background:#fff"><td style="padding:10px 16px;color:#6b7280;font-size:14px">📌 Type</td>
                  <td style="padding:10px 16px;color:#374151">{{type}}</td></tr>
              <tr><td style="padding:10px 16px;color:#6b7280;font-size:14px">👤 Avec</td>
                  <td style="padding:10px 16px;color:#374151">{{assignedTo}}</td></tr>
            </table>
            {{meetLink}}
            <hr style="border:none;border-top:1px solid #e5e7eb;margin:24px 0">
            <p style="margin:0 0 8px;color:#374151;font-size:14px">Besoin d'annuler ?</p>
            <a href="{{cancelUrl}}"
               style="display:inline-block;padding:10px 20px;background:#fee2e2;color:#dc2626;border-radius:6px;text-decoration:none;font-weight:600;font-size:14px">
              ❌ Annuler ce rendez-vous
            </a>
            <p style="margin:24px 0 0;color:#9ca3af;font-size:12px">
              Pour modifier votre créneau, annulez d'abord puis reprenez une nouvelle réservation.
            </p>
          </td></tr>
        </table>
        </td></tr></table>
        </body></html>
        """;

    private static final String BOOKING_CANCELLATION_HTML = """
        <!DOCTYPE html>
        <html><body style="margin:0;padding:0;background:#f8fafc;font-family:'Segoe UI',Arial,sans-serif">
        <table width="100%" cellpadding="0" cellspacing="0" style="background:#f8fafc;padding:32px 0">
        <tr><td align="center">
        <table width="600" cellpadding="0" cellspacing="0" style="background:#fff;border-radius:12px;box-shadow:0 2px 8px rgba(0,0,0,.06);overflow:hidden">
          <tr><td style="background:#dc2626;padding:28px 32px">
            <h1 style="margin:0;color:#fff;font-size:22px;font-weight:700">❌ Rendez-vous annulé</h1>
          </td></tr>
          <tr><td style="padding:32px">
            <p style="margin:0 0 16px;color:#374151;font-size:16px">Bonjour <strong>{{clientName}}</strong>,</p>
            <p style="margin:0 0 24px;color:#374151">
              Votre rendez-vous <strong>{{bookingTitle}}</strong> du <strong>{{date}}</strong> à <strong>{{time}}</strong> a été annulé.
            </p>
            <p style="margin:0 0 24px;color:#374151">Si vous souhaitez prendre un nouveau rendez-vous :</p>
            <a href="{{newBookingUrl}}"
               style="display:inline-block;padding:12px 24px;background:#6366f1;color:#fff;border-radius:6px;text-decoration:none;font-weight:600">
              📅 Prendre un nouveau rendez-vous
            </a>
          </td></tr>
        </table>
        </td></tr></table>
        </body></html>
        """;

    private static final String TICKET_CREATED_HTML = """
        <!DOCTYPE html>
        <html><body style="margin:0;padding:0;background:#f8fafc;font-family:'Segoe UI',Arial,sans-serif">
        <table width="100%" cellpadding="0" cellspacing="0" style="background:#f8fafc;padding:32px 0">
        <tr><td align="center">
        <table width="600" cellpadding="0" cellspacing="0" style="background:#fff;border-radius:12px;box-shadow:0 2px 8px rgba(0,0,0,.06);overflow:hidden">
          <tr><td style="background:#0ea5e9;padding:28px 32px">
            <h1 style="margin:0;color:#fff;font-size:22px;font-weight:700">🎫 Demande reçue</h1>
          </td></tr>
          <tr><td style="padding:32px">
            <p style="margin:0 0 16px;color:#374151">Votre demande <strong>#{{ticketId}}</strong> a bien été enregistrée.</p>
            <p style="margin:0 0 8px;color:#374151;font-weight:600">{{subject}}</p>
            <p style="margin:0 0 24px;color:#6b7280;font-size:14px">{{description}}</p>
            <a href="{{trackUrl}}"
               style="display:inline-block;padding:12px 24px;background:#0ea5e9;color:#fff;border-radius:6px;text-decoration:none;font-weight:600">
              Suivre ma demande
            </a>
          </td></tr>
        </table>
        </td></tr></table>
        </body></html>
        """;

    private static final String TICKET_REPLY_HTML = """
        <!DOCTYPE html>
        <html><body style="margin:0;padding:0;background:#f8fafc;font-family:'Segoe UI',Arial,sans-serif">
        <table width="100%" cellpadding="0" cellspacing="0" style="background:#f8fafc;padding:32px 0">
        <tr><td align="center">
        <table width="600" cellpadding="0" cellspacing="0" style="background:#fff;border-radius:12px;box-shadow:0 2px 8px rgba(0,0,0,.06);overflow:hidden">
          <tr><td style="background:#7c3aed;padding:28px 32px">
            <h1 style="margin:0;color:#fff;font-size:22px;font-weight:700">💬 Nouvelle réponse</h1>
          </td></tr>
          <tr><td style="padding:32px">
            <p style="margin:0 0 8px;color:#374151">Réponse à votre demande <strong>#{{ticketId}}</strong> — {{subject}}</p>
            <div style="background:#f8fafc;border-left:3px solid #7c3aed;padding:16px;margin:16px 0;color:#374151">{{replyBody}}</div>
            <a href="{{trackUrl}}"
               style="display:inline-block;padding:12px 24px;background:#7c3aed;color:#fff;border-radius:6px;text-decoration:none;font-weight:600">
              Répondre / Voir le ticket
            </a>
          </td></tr>
        </table>
        </td></tr></table>
        </body></html>
        """;

    private static final String INVOICE_SENT_HTML = """
        <!DOCTYPE html>
        <html><body style="margin:0;padding:0;background:#f8fafc;font-family:'Segoe UI',Arial,sans-serif">
        <table width="100%" cellpadding="0" cellspacing="0" style="background:#f8fafc;padding:32px 0">
        <tr><td align="center">
        <table width="600" cellpadding="0" cellspacing="0" style="background:#fff;border-radius:12px;box-shadow:0 2px 8px rgba(0,0,0,.06);overflow:hidden">
          <tr><td style="background:#059669;padding:28px 32px">
            <h1 style="margin:0;color:#fff;font-size:22px;font-weight:700">🧾 Votre facture</h1>
          </td></tr>
          <tr><td style="padding:32px">
            <p style="margin:0 0 16px;color:#374151">Votre facture <strong>n°{{invoiceNumber}}</strong> est disponible.</p>
            <table width="100%" style="background:#f8fafc;border-radius:8px;margin-bottom:24px">
              <tr><td style="padding:10px 16px;color:#6b7280;font-size:14px">Montant TTC</td>
                  <td style="padding:10px 16px;font-weight:700;color:#059669;font-size:18px">{{amount}}</td></tr>
              <tr><td style="padding:10px 16px;color:#6b7280;font-size:14px">Échéance</td>
                  <td style="padding:10px 16px;font-weight:600;color:#374151">{{dueDate}}</td></tr>
            </table>
            <a href="{{downloadUrl}}"
               style="display:inline-block;padding:12px 24px;background:#059669;color:#fff;border-radius:6px;text-decoration:none;font-weight:600">
              📥 Télécharger la facture
            </a>
          </td></tr>
        </table>
        </td></tr></table>
        </body></html>
        """;

    private static final String QUOTE_SENT_HTML = """
        <!DOCTYPE html>
        <html><body style="margin:0;padding:0;background:#f8fafc;font-family:'Segoe UI',Arial,sans-serif">
        <table width="100%" cellpadding="0" cellspacing="0" style="background:#f8fafc;padding:32px 0">
        <tr><td align="center">
        <table width="600" cellpadding="0" cellspacing="0" style="background:#fff;border-radius:12px;box-shadow:0 2px 8px rgba(0,0,0,.06);overflow:hidden">
          <tr><td style="background:#f59e0b;padding:28px 32px">
            <h1 style="margin:0;color:#fff;font-size:22px;font-weight:700">📄 Votre devis</h1>
          </td></tr>
          <tr><td style="padding:32px">
            <p style="margin:0 0 16px;color:#374151">Votre devis <strong>n°{{quoteNumber}}</strong> est prêt.</p>
            <table width="100%" style="background:#f8fafc;border-radius:8px;margin-bottom:24px">
              <tr><td style="padding:10px 16px;color:#6b7280;font-size:14px">Montant HT</td>
                  <td style="padding:10px 16px;font-weight:700;color:#374151;font-size:18px">{{amount}}</td></tr>
              <tr><td style="padding:10px 16px;color:#6b7280;font-size:14px">Valable jusqu'au</td>
                  <td style="padding:10px 16px;font-weight:600;color:#374151">{{validUntil}}</td></tr>
            </table>
            <a href="{{downloadUrl}}"
               style="display:inline-block;padding:12px 24px;background:#f59e0b;color:#fff;border-radius:6px;text-decoration:none;font-weight:600">
              📥 Voir le devis
            </a>
          </td></tr>
        </table>
        </td></tr></table>
        </body></html>
        """;

    private static final String REGISTRATION_CONFIRMATION_HTML = """
        <!DOCTYPE html>
        <html><body style="margin:0;padding:0;background:#f8fafc;font-family:'Segoe UI',Arial,sans-serif">
        <table width="100%" cellpadding="0" cellspacing="0" style="background:#f8fafc;padding:32px 0">
        <tr><td align="center">
        <table width="600" cellpadding="0" cellspacing="0" style="background:#fff;border-radius:12px;box-shadow:0 2px 8px rgba(0,0,0,.06);overflow:hidden">
          <tr><td style="background:#0ea5e9;padding:28px 32px">
            <h1 style="margin:0;color:#fff;font-size:22px;font-weight:700">✉️ Confirmez votre adresse email</h1>
          </td></tr>
          <tr><td style="padding:32px">
            <p style="margin:0 0 16px;color:#374151;font-size:16px">Bonjour <strong>{{fullName}}</strong>,</p>
            <p style="margin:0 0 24px;color:#374151">
              Merci de vous être inscrit(e) pour <strong>{{companyName}}</strong>.<br>
              Pour activer votre compte, veuillez confirmer votre adresse email en cliquant sur le bouton ci-dessous.
            </p>
            <div style="text-align:center;margin:0 0 24px">
              <a href="{{verifyUrl}}"
                 style="display:inline-block;padding:14px 32px;background:#0ea5e9;color:#fff;border-radius:8px;text-decoration:none;font-weight:700;font-size:16px">
                Confirmer mon adresse email
              </a>
            </div>
            <hr style="border:none;border-top:1px solid #e5e7eb;margin:24px 0">
            <p style="margin:0 0 8px;color:#9ca3af;font-size:12px">
              Si vous n'êtes pas à l'origine de cette inscription, ignorez cet email.
            </p>
            <p style="margin:0;color:#9ca3af;font-size:12px">
              Si le bouton ne fonctionne pas, copiez ce lien dans votre navigateur :<br>
              <span style="color:#6b7280;word-break:break-all">{{verifyUrl}}</span>
            </p>
          </td></tr>
        </table>
        </td></tr></table>
        </body></html>
        """;

    private static final String PASSWORD_RESET_HTML = """
        <!DOCTYPE html>
        <html><body style="margin:0;padding:0;background:#f8fafc;font-family:'Segoe UI',Arial,sans-serif">
        <table width="100%" cellpadding="0" cellspacing="0" style="background:#f8fafc;padding:32px 0">
        <tr><td align="center">
        <table width="600" cellpadding="0" cellspacing="0" style="background:#fff;border-radius:12px;box-shadow:0 2px 8px rgba(0,0,0,.06);overflow:hidden">
          <tr><td style="background:#4f46e5;padding:28px 32px">
            <h1 style="margin:0;color:#fff;font-size:22px;font-weight:700">🔐 Réinitialisation de votre mot de passe</h1>
          </td></tr>
          <tr><td style="padding:32px">
            <p style="margin:0 0 16px;color:#374151;font-size:16px">Bonjour <strong>{{fullName}}</strong>,</p>
            <p style="margin:0 0 24px;color:#374151">
              Vous avez demandé la réinitialisation de votre mot de passe. Cliquez sur le bouton ci-dessous pour choisir un nouveau mot de passe.
            </p>
            <p style="margin:0 0 24px;color:#374151">
              Ce lien est valable <strong>{{expiresIn}}</strong>.
            </p>
            <div style="text-align:center;margin:0 0 24px">
              <a href="{{resetUrl}}"
                 style="display:inline-block;padding:14px 32px;background:#4f46e5;color:#fff;border-radius:8px;text-decoration:none;font-weight:700;font-size:16px">
                Réinitialiser mon mot de passe
              </a>
            </div>
            <div style="background:#fef3c7;border-left:4px solid #f59e0b;padding:16px;border-radius:4px;margin-bottom:24px">
              <p style="margin:0 0 8px;color:#92400e;font-weight:600">⚠️ Avertissement de sécurité</p>
              <p style="margin:0;color:#92400e;font-size:14px">
                Si vous n'êtes pas à l'origine de cette demande, ignorez cet email. Votre mot de passe ne sera pas modifié.
                En cas de doute sur la sécurité de votre compte, contactez-nous immédiatement.
              </p>
            </div>
            <hr style="border:none;border-top:1px solid #e5e7eb;margin:24px 0">
            <p style="margin:0;color:#9ca3af;font-size:12px">
              Si le bouton ne fonctionne pas, copiez ce lien dans votre navigateur :<br>
              <span style="color:#6b7280;word-break:break-all">{{resetUrl}}</span>
            </p>
          </td></tr>
        </table>
        </td></tr></table>
        </body></html>
        """;
}
