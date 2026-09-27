package dev.localsoul.aero.actor.supervision;

import dev.localsoul.aero.actor.Actor;

/**
 * Erzeugt ein Kind. Der Name wird uebergeben, damit ein Supervisor viele gleich
 * artige Namen vergeben kann ({@code room-1} … {@code room-500}).
 */
@FunctionalInterface
public interface ChildFactory {

    Actor create(String name);
}
