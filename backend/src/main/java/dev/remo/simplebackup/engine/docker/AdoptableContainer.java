package dev.remo.simplebackup.engine.docker;

/**
 * Ein beim Start vorgefundener Container, der zu einem bekannten Schritt gehoert.
 *
 * @param containerId Kennung bei Docker
 * @param executionId Kennung des Schritts aus dem Label
 * @param running     true, wenn er noch laeuft. Dann kann das Backend sich wieder anhaengen;
 *                    andernfalls ist nur noch das Ergebnis auszuwerten.
 */
public record AdoptableContainer(String containerId, String executionId, boolean running) {
}
