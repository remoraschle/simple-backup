package dev.remo.simplebackup.snapshot;

import dev.remo.simplebackup.catalog.TargetConfig;
import dev.remo.simplebackup.engine.MountTranslator;
import dev.remo.simplebackup.engine.PathTranslationException;
import dev.remo.simplebackup.engine.VolumeMount;
import dev.remo.simplebackup.restic.ResticRepository;
import dev.remo.simplebackup.secret.CredentialService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Macht aus der Konfiguration eines Ziels ein ansprechbares restic-Repository.
 *
 * <p>Eine einzige Stelle dafuer, weil Schreiben und Lesen dieselbe Adresse und dieselben
 * Zugangsdaten brauchen: Ein Wiederherstellen, das das Repository anders adressiert als die
 * Sicherung, findet nichts -- und zwar genau dann, wenn man es braucht.
 *
 * <p>Klartext entsteht erst hier, unmittelbar vor dem Start eines Runners, und wird dabei
 * beim Redaktor angemeldet.
 */
@Component
public class ResticTargets {

    private final CredentialService credentials;
    private final MountTranslator mountTranslator;
    private final ObjectMapper objectMapper;

    public ResticTargets(CredentialService credentials, MountTranslator mountTranslator,
            ObjectMapper objectMapper) {
        this.credentials = credentials;
        this.mountTranslator = mountTranslator;
        this.objectMapper = objectMapper;
    }

    /**
     * @param targetName nur fuer die Fehlermeldung -- eine Kennung hilft beim Suchen nicht
     */
    public ResticRepository repositoryFor(TargetConfig config, String targetName) {
        UUID passwordId = config.repositoryPasswordCredentialId();

        if (passwordId == null) {
            throw new IllegalStateException(
                    "Dem Ziel '%s' fehlt das Repository-Passwort".formatted(targetName));
        }
        String password = credentials.reveal(passwordId);

        return switch (config) {
            case TargetConfig.LocalPath localPath -> ResticRepository.localPath(
                    pathInRunner(localPath.path()), password);

            case TargetConfig.S3 s3 -> {
                var keys = objectMapper.readValue(credentials.reveal(s3.credentialId()),
                        S3Credentials.class);
                yield ResticRepository.s3(s3.endpoint(), s3.bucket(), s3.prefix(),
                        keys.accessKeyId(), keys.secretAccessKey(), password);
            }
        };
    }

    /** Die Einhaengung, die der Runner braucht, um an das Ziel heranzukommen. */
    public List<VolumeMount> mountsFor(TargetConfig config) {
        List<VolumeMount> mounts = new ArrayList<>();

        if (config instanceof TargetConfig.LocalPath localPath) {
            mounts.add(translate(localPath.path(), false));
        }
        return mounts;
    }

    /** Im Runner erscheinen die Pfade unter derselben Adresse wie im Backend. */
    public String pathInRunner(String containerPath) {
        return translate(containerPath, false).target();
    }

    public VolumeMount translate(String containerPath, boolean readOnly) {
        try {
            return mountTranslator.translate(containerPath, readOnly);
        } catch (PathTranslationException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }
}
