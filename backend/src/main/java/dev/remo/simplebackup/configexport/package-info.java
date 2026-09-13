/**
 * Konfiguration ausgeben und wieder einlesen.
 *
 * <p>Der Ausweg aus dem einen Fehler, den diese Anwendung sonst nicht verzeiht: Ohne
 * Masterkey ist die eigene Datenbank wertlos -- alle Zugangsdaten darin sind unlesbar, und
 * damit kommt man an kein Repository mehr heran. Ein Archiv, das mit einem selbst gewaehlten
 * Passwort verschluesselt ist, haengt nicht am Masterkey und kann ausserhalb liegen.
 *
 * <p>Es ist bewusst kein Backup der Daten, sondern nur der Konfiguration: Quellen, Ziele,
 * Plaene, Aufbewahrungsregeln, Kanaele und die Zugangsdaten. Damit laesst sich die Anwendung
 * anderswo neu aufsetzen und die vorhandenen Repositories weiter benutzen.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Konfig-Export")
package dev.remo.simplebackup.configexport;
