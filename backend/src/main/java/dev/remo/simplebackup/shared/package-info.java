/**
 * Bausteine ohne eigene Fachlichkeit, die jedes Modul verwenden darf.
 *
 * <p>Als offenes Modul deklariert: Andere Module duerfen hier auch auf Unterpakete
 * zugreifen, ohne dass Spring Modulith das als Grenzverletzung meldet.
 */
@org.springframework.modulith.ApplicationModule(type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package dev.remo.simplebackup.shared;
