package dev.localsoul.aero.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.localsoul.aero.game.world.Projectile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Projektilsimulation (Monster-Bullets)")
class ProjectileSimulationTest {

    @Test
    @DisplayName("Position = start + ageMs*speed/10000 entlang angle")
    void motion() {
        final Projectile proj = new Projectile(7, 0, 0, 10f, 10f, 0f /* Winkel 0 */,
                40, 50, 3000);
        proj.simulate(1000);
        assertThat(proj.x()).as("50/10000 * 1000ms = 5 Einheiten").isCloseTo(15f, within(1e-3f));
        assertThat(proj.y()).isCloseTo(10f, within(1e-3f));
        assertThat(proj.expired()).isFalse();
    }

    @Test
    @DisplayName("Lifetime-Ablauf (3000 ms) → expired")
    void expiry() {
        final Projectile proj = new Projectile(7, 0, 0, 0f, 0f, 0f, 40, 50, 3000);
        proj.simulate(3000);
        assertThat(proj.expired()).isTrue();
    }

    @Test
    @DisplayName("Reichweite bei Lifetime = speed*3000/10000 = 15 Einheiten")
    void range() {
        final Projectile proj = new Projectile(7, 0, 0, 0f, 0f, 0f, 40, 50, 3000);
        proj.simulate(3000);
        assertThat(proj.x()).as("max. Reichweite").isCloseTo(15f, within(1e-3f));
    }
}