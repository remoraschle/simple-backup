package dev.remo.simplebackup.notification;

import dev.remo.simplebackup.secret.CredentialService;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

/**
 * Versand per E-Mail ueber einen eigenen Mailserver.
 *
 * <p>Der Kanal, den jeder hat. Fuer den Alarm mitten in der Nacht ist er die schlechtere
 * Wahl als ein Push mit Quittierungspflicht -- als zweiter Weg daneben ist er die richtige:
 * Er kommt auch dann noch an, wenn das Telefon gewechselt wurde oder ein Dienst seinen
 * Betrieb einstellt.
 *
 * <p>Der Server steht in der Konfiguration des Kanals und nicht in der der Anwendung. Das
 * ist Absicht: So laesst sich ein Kanal ueber die Oberflaeche einrichten und aendern, ohne
 * die Anwendung neu zu starten -- und ein zweiter Kanal ueber einen anderen Server ist kein
 * Sonderfall.
 */
@Component
class SmtpTransport implements NotificationTransport {

    /** Ab hier ist die Verbindung von Anfang an verschluesselt, nicht erst nach STARTTLS. */
    private static final int IMPLICIT_TLS_PORT = 465;

    private final CredentialService credentials;

    SmtpTransport(CredentialService credentials) {
        this.credentials = credentials;
    }

    @Override
    public ChannelType type() {
        return ChannelType.SMTP;
    }

    @Override
    public String send(ChannelConfig config, OutboxEntry entry) {
        ChannelConfig.Smtp smtp = (ChannelConfig.Smtp) config;

        var message = new org.springframework.mail.SimpleMailMessage();
        message.setFrom(smtp.from());
        message.setTo(smtp.recipients().toArray(String[]::new));
        message.setSubject(subjectOf(entry));
        message.setText(entry.getBody());

        try {
            senderFor(smtp).send(message);
            return null;

        } catch (MailException e) {
            // Die Meldung des Mailservers ist hier die nuetzliche Information -- sie nennt
            // abgelehnte Adressen, Anmeldefehler und Zertifikatsprobleme beim Namen.
            throw new NotificationException("Der Mailserver nahm die Meldung nicht an: "
                    + String.valueOf(e.getMessage()), e);
        }
    }

    /**
     * Die Stufe gehoert in den Betreff.
     *
     * <p>In einer Liste von hundert Nachrichten entscheidet sich an dieser Zeile, ob jemand
     * heute Nacht noch aufsteht.
     */
    private static String subjectOf(OutboxEntry entry) {
        return "[%s] %s".formatted(entry.getSeverity(), entry.getTitle());
    }

    /**
     * Ein Versender je Zustellung.
     *
     * <p>Teurer als ein gemeinsamer, aber richtig: Jeder Kanal hat seinen eigenen Server,
     * und eine Aenderung an einem Kanal soll sofort gelten und nicht erst nach einem
     * Neustart.
     */
    private JavaMailSenderImpl senderFor(ChannelConfig.Smtp smtp) {
        var sender = new JavaMailSenderImpl();
        sender.setHost(smtp.host());
        sender.setPort(smtp.port());
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());

        if (smtp.username() != null) {
            sender.setUsername(smtp.username());
            sender.setPassword(credentials.reveal(smtp.credentialId()));
        }

        Properties properties = sender.getJavaMailProperties();
        properties.put("mail.transport.protocol", "smtp");
        properties.put("mail.smtp.auth", String.valueOf(smtp.username() != null));
        properties.put("mail.smtp.starttls.enable", String.valueOf(smtp.startTls()));
        // Kein Rueckfall auf unverschluesselt: Wer Verschluesselung verlangt hat, soll
        // lieber eine Fehlermeldung bekommen als eine Meldung im Klartext ueber das Netz.
        properties.put("mail.smtp.starttls.required", String.valueOf(smtp.startTls()));

        if (smtp.port() == IMPLICIT_TLS_PORT) {
            properties.put("mail.smtp.ssl.enable", "true");
        }

        // Ein haengender Mailserver darf den Postausgang nicht blockieren: Der naechste
        // Durchgang kommt in Sekunden, und der Eintrag bleibt bis dahin offen.
        properties.put("mail.smtp.connectiontimeout", "10000");
        properties.put("mail.smtp.timeout", "20000");
        properties.put("mail.smtp.writetimeout", "20000");

        return sender;
    }
}
