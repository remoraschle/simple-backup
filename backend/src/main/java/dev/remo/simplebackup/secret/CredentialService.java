package dev.remo.simplebackup.secret;

import dev.remo.simplebackup.shared.NotFoundException;
import dev.remo.simplebackup.shared.SecretRedactor;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Einzige Stelle, an der Zugangsdaten entstehen, gelesen und geloescht werden.
 *
 * <p>Die Trennung zwischen {@link #find} und {@link #reveal} ist Absicht: Alles, was die API
 * ausliefert, geht ueber {@code find} und kann konstruktionsbedingt kein Geheimnis
 * enthalten. {@code reveal} liefert Klartext und wird ausschliesslich beim Bestuecken eines
 * Runner-Containers aufgerufen.
 */
@Service
@Transactional
public class CredentialService {

    private final CredentialRepository repository;
    private final SecretCipher cipher;
    private final SecretRedactor redactor;

    CredentialService(CredentialRepository repository, SecretCipher cipher, SecretRedactor redactor) {
        this.repository = repository;
        this.cipher = cipher;
        this.redactor = redactor;
    }

    public CredentialSummary create(String name, CredentialType type, String description, String plaintext) {
        if (repository.existsByName(name)) {
            throw new IllegalArgumentException("Ein Zugang mit dem Namen '%s' existiert bereits".formatted(name));
        }
        Credential credential = new Credential(name, type, description, cipher.encrypt(plaintext));
        return CredentialSummary.of(repository.save(credential));
    }

    /** Ersetzt den Geheimniswert; Name und Typ bleiben, damit Verweise gueltig bleiben. */
    public CredentialSummary rotate(UUID id, String newPlaintext) {
        Credential credential = require(id);
        credential.applySecret(cipher.encrypt(newPlaintext));
        return CredentialSummary.of(repository.save(credential));
    }

    @Transactional(readOnly = true)
    public List<CredentialSummary> findAll() {
        return repository.findAll().stream().map(CredentialSummary::of).toList();
    }

    @Transactional(readOnly = true)
    public CredentialSummary find(UUID id) {
        return CredentialSummary.of(require(id));
    }

    /**
     * Liefert den Klartext und meldet ihn zugleich beim Redaktor an, damit er aus allen
     * Logzeilen und gespeicherten Kommandozeilen dieses Laufs herausgefiltert wird.
     *
     * <p>Nur fuer das Bestuecken eines Runner-Containers. Der Rueckgabewert darf weder in
     * einer API-Antwort noch in einer Logausgabe landen.
     */
    @Transactional(readOnly = true)
    public String reveal(UUID id) {
        String plaintext = cipher.decrypt(require(id).toEncryptedSecret());
        redactor.register(plaintext);
        return plaintext;
    }

    public void delete(UUID id) {
        repository.delete(require(id));
    }

    private Credential require(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Zugang %s nicht gefunden".formatted(id)));
    }
}
