package dev.remo.simplebackup.run;

import dev.remo.simplebackup.catalog.ExecutablePlan;
import dev.remo.simplebackup.catalog.SourceConfig;
import dev.remo.simplebackup.catalog.SourceType;
import dev.remo.simplebackup.engine.VolumeMount;
import dev.remo.simplebackup.snapshot.ResticTargets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Die einfachste Quelle: Die Daten liegen schon da.
 *
 * <p>Eingehaengt wird schreibgeschuetzt. Das Werkzeug hat auf Originaldaten nichts zu
 * schreiben, und ein schreibgeschuetzter Mount macht einen Fehler unmoeglich statt nur
 * unwahrscheinlich.
 */
@Component
class LocalPathProducer implements SourceProducer {

    private final ResticTargets targets;

    LocalPathProducer(ResticTargets targets) {
        this.targets = targets;
    }

    @Override
    public SourceType type() {
        return SourceType.LOCAL_PATH;
    }

    @Override
    public PreparedSource prepare(ExecutablePlan plan, String stagingDirectory,
            RunProgressListener listener) {

        SourceConfig.LocalPath source = (SourceConfig.LocalPath) plan.source();

        List<VolumeMount> mounts = new ArrayList<>();
        List<String> paths = new ArrayList<>();

        for (String path : source.paths()) {
            VolumeMount mount = targets.translate(path, true);
            mounts.add(mount);
            paths.add(mount.target());
        }
        return PreparedSource.directly(paths, mounts, source.excludes());
    }
}
