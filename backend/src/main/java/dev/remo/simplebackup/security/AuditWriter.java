package dev.remo.simplebackup.security;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Schreibt einen Eintrag des Pruefprotokolls in seiner eigenen Transaktion.
 *
 * <p><b>Eine eigene Bean und keine Methode im Dienst selbst.</b> Zwei Gruende: Spring legt
 * {@code @Transactional} als Stellvertreter um die Bean, ein Aufruf innerhalb derselben
 * Klasse ginge daran vorbei. Und ein Fehler beim Schreiben markiert die Transaktion als
 * "nur zurueckrollen" -- das faellt erst beim Abschluss auf, also ausserhalb jedes
 * {@code try} innerhalb der Methode. Erst die Trennung macht den Fehler abfangbar.
 */
@Component
class AuditWriter {

    private final AuditLogRepository repository;

    AuditWriter(AuditLogRepository repository) {
        this.repository = repository;
    }

    /**
     * In einer eigenen Transaktion: Ein Eintrag muss auch dann erhalten bleiben, wenn die
     * ausloesende Aktion anschliessend zurueckgerollt wird -- gerade der fehlgeschlagene
     * Loeschversuch ist der interessante.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void write(AuditLog entry) {
        repository.save(entry);
    }
}
