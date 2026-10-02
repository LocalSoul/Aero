package dev.localsoul.aero.game;

import static org.assertj.core.api.Assertions.assertThat;

import dev.localsoul.aero.game.world.Entity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Damage-Formel (Client-Ident)")
class DamageFormulaTest {

    @Test
    @DisplayName("max(dmg*3/20, dmg-defense) — wie GameObject.damageWithDefense")
    void damageWithDefense() {
        assertThat(Entity.damageWithDefense(40, 0)).as("keine Rüstung").isEqualTo(40);
        assertThat(Entity.damageWithDefense(40, 40)).isEqualTo(6);      // 40*3/20
        assertThat(Entity.damageWithDefense(20, 8)).isEqualTo(12);      // 20-8
        assertThat(Entity.damageWithDefense(20, 0)).isEqualTo(20);
        assertThat(Entity.damageWithDefense(7, 100)).isEqualTo(1);      // 7*3/20 = 1
        assertThat(Entity.damageWithDefense(10, 25)).isEqualTo(1);      // 10*3/20 = 1
    }
}