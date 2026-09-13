/**
 * Benachrichtigungen ueber Pushover und beliebige Webhooks.
 *
 * <p>Der Versand laeuft ueber einen Postausgang in der Datenbank und nicht direkt aus dem
 * Lauf heraus. Der Grund ist der Zweck der Sache: Eine Alarmmeldung, die selbst verloren
 * geht -- weil gerade kein Netz da war oder der Dienst antwortete -- ist schlimmer als keine,
 * denn man haelt das Schweigen fuer ein gutes Zeichen. Aus dem Postausgang wird so lange
 * erneut zugestellt, bis es klappt oder die Versuche erschoepft sind; beides ist sichtbar.
 *
 * <p>Das Modul kennt weder Plaene noch Laeufe. Es bekommt fertige Meldungen und weiss nur,
 * wohin damit.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Benachrichtigung")
package dev.remo.simplebackup.notification;
