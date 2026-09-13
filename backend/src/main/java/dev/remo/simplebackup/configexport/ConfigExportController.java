package dev.remo.simplebackup.configexport;

import dev.remo.simplebackup.security.AuditService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.security.Principal;
import java.time.LocalDate;
import java.util.Arrays;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Ausgabe und Einspielen der Konfiguration.
 *
 * <p>Beides sind POST-Aufrufe, auch die Ausgabe: Das Passwort gehoert nicht in eine Adresse,
 * die im Verlauf des Browsers und in jedem Zugriffsprotokoll steht. Zugleich bleibt der
 * Aufruf damit dem Administrator vorbehalten -- alles Veraendernde ist es, und ein Archiv
 * mit saemtlichen Zugangsdaten darf niemand anlegen duerfen, der nur zuschauen soll.
 */
@RestController
@RequestMapping("/api/config")
class ConfigExportController {

    private final ConfigExportService service;
    private final AuditService auditService;

    ConfigExportController(ConfigExportService service, AuditService auditService) {
        this.service = service;
        this.auditService = auditService;
    }

    @PostMapping(value = "/export", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    ResponseEntity<byte[]> export(@Valid @RequestBody PasswordRequest request, Principal principal) {
        char[] password = request.password().toCharArray();
        try {
            byte[] archive = service.export(password);

            auditService.record(principal.getName(), "CONFIG_EXPORTED", "config", null,
                    "{\"bytes\":%d}".formatted(archive.length));

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"simple-backup-%s.sbexp\"".formatted(LocalDate.now()))
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(archive);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /**
     * Als Datei-Upload und nicht als JSON: Das Archiv ist binaer, und ein Umweg ueber Base64
     * wuerde es nur aufblaehen, damit es am Ende wieder binaer ist.
     */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ConfigExportService.ImportReport importArchive(@RequestParam("file") MultipartFile file,
            @RequestParam("password") String password, Principal principal) throws IOException {

        if (file.isEmpty()) {
            throw new IllegalArgumentException("Es wurde keine Datei uebergeben");
        }

        char[] characters = password.toCharArray();
        try {
            var report = service.importArchive(file.getBytes(), characters);

            auditService.record(principal.getName(), "CONFIG_IMPORTED", "config", null,
                    "{\"imported\":%d}".formatted(
                            report.imported().values().stream().mapToInt(Integer::intValue).sum()));

            return report;
        } finally {
            Arrays.fill(characters, '\0');
        }
    }

    /** @param password Passwort des Archivs, nicht das der Anmeldung */
    record PasswordRequest(
            @NotBlank @Size(min = PasswordArchive.MINIMUM_PASSWORD_LENGTH, max = 200) String password) {
    }
}
