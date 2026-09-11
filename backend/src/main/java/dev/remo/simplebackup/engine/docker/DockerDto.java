package dev.remo.simplebackup.engine.docker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Die Ausschnitte der Docker-API, die dieses Werkzeug braucht.
 *
 * <p>Bewusst nur die tatsaechlich verwendeten Felder: Die API liefert erheblich mehr, und
 * jedes zusaetzlich abgebildete Feld waere eine Stelle, die bei einem Versionswechsel
 * brechen kann. Unbekannte Felder werden ignoriert.
 */
final class DockerDto {

    private DockerDto() {
    }

    /** Anfrage an {@code POST /containers/create}. */
    record CreateContainer(
            @JsonProperty("Image") String image,
            @JsonProperty("Cmd") List<String> cmd,
            @JsonProperty("Env") List<String> env,
            @JsonProperty("Labels") Map<String, String> labels,
            @JsonProperty("User") String user,
            @JsonProperty("Tty") boolean tty,
            @JsonProperty("AttachStdout") boolean attachStdout,
            @JsonProperty("AttachStderr") boolean attachStderr,
            @JsonProperty("HostConfig") HostConfig hostConfig) {
    }

    record HostConfig(
            @JsonProperty("Binds") List<String> binds,
            @JsonProperty("Memory") Long memory,
            @JsonProperty("NanoCpus") Long nanoCpus,
            @JsonProperty("NetworkMode") String networkMode,
            @JsonProperty("AutoRemove") boolean autoRemove,
            @JsonProperty("ReadonlyRootfs") boolean readonlyRootfs,
            @JsonProperty("CapDrop") List<String> capDrop,
            @JsonProperty("SecurityOpt") List<String> securityOpt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CreatedContainer(
            @JsonProperty("Id") String id,
            @JsonProperty("Warnings") List<String> warnings) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WaitResult(@JsonProperty("StatusCode") int statusCode) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ContainerDetails(
            @JsonProperty("Id") String id,
            @JsonProperty("Name") String name,
            @JsonProperty("State") ContainerState state,
            @JsonProperty("Config") ContainerConfig config,
            @JsonProperty("Mounts") List<MountPoint> mounts) {
    }

    /**
     * Antwortfelder bewusst als Huellentypen statt als primitive Typen: Die Docker-API laesst
     * Felder je nach Zustand und Version weg, und Jackson 3 lehnt es ab, fehlende Werte in
     * primitive Typen abzubilden. Ein Huellentyp bildet "nicht geliefert" korrekt ab.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ContainerState(
            @JsonProperty("Status") String status,
            @JsonProperty("Running") Boolean running,
            @JsonProperty("ExitCode") Integer exitCode,
            @JsonProperty("OOMKilled") Boolean oomKilled,
            @JsonProperty("Error") String error) {

        boolean isRunning() {
            return Boolean.TRUE.equals(running);
        }

        /**
         * Ob der Container wegen Speichermangels beendet wurde. Der haeufigste Grund fuer
         * einen scheinbar grundlos abgebrochenen Lauf -- die Meldung soll das benennen.
         */
        boolean wasKilledForMemory() {
            return Boolean.TRUE.equals(oomKilled);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ContainerConfig(@JsonProperty("Labels") Map<String, String> labels) {
    }

    /**
     * Ein Eintrag der Mount-Tabelle.
     *
     * <p>Grundlage der Host-Pfad-Uebersetzung: {@code Source} ist der Pfad auf dem Host oder
     * der Name eines Volumes, {@code Destination} der Pfad im Container.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record MountPoint(
            @JsonProperty("Type") String type,
            @JsonProperty("Name") String name,
            @JsonProperty("Source") String source,
            @JsonProperty("Destination") String destination,
            @JsonProperty("RW") Boolean readWrite) {

        boolean isVolume() {
            return "volume".equals(type);
        }

        boolean isReadOnly() {
            return Boolean.FALSE.equals(readWrite);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ContainerSummary(
            @JsonProperty("Id") String id,
            @JsonProperty("State") String state,
            @JsonProperty("Labels") Map<String, String> labels) {

        boolean isRunning() {
            return "running".equals(state);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SystemInfo(
            @JsonProperty("SecurityOptions") List<String> securityOptions,
            @JsonProperty("ServerVersion") String serverVersion,
            @JsonProperty("OperatingSystem") String operatingSystem) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ImageSummary(@JsonProperty("RepoTags") List<String> repoTags) {
    }
}
