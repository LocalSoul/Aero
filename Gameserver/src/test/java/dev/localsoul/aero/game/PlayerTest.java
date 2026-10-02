package dev.localsoul.aero.game;

import static org.assertj.core.api.Assertions.assertThat;

import dev.localsoul.aero.game.world.Map;
import dev.localsoul.aero.game.world.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Player: Regen, XP/Level, Inventar, Schuss-Spur")
class PlayerTest {

    private static Player player() {
        return new Player(1, new Map(50, 50), 25f, 25f);
    }

    @Test
    @DisplayName("HP-Regeneration: hpRegen*dt, gedeckelt auf maxHp")
    void regen() {
        final Player p = player();
        p.damage(30);
        assertThat(p.hp()).isEqualTo(70);
        p.simulate(1.0f);                       // 12 HP/s
        assertThat(p.hp()).isEqualTo(82);
        p.simulate(5.0f);                       // 82 + 60 → gedeckelt
        assertThat(p.hp()).isEqualTo(100);
    }

    @Test
    @DisplayName("XP/Level: exp +=, Level-Up mit vereinfachter Kurve")
    void expAndLevel() {
        final Player p = player();
        assertThat(p.status().stats).anyMatch(s -> s.statType == 7 && s.statValue == 1);
        p.addExp(60);
        assertThat(p.status().stats).anyMatch(s -> s.statType == 6 && s.statValue == 60);
        p.addExp(40);                           // = 100 → Level 2
        assertThat(p.status().stats).anyMatch(s -> s.statType == 7 && s.statValue == 2);
        assertThat(p.status().stats).anyMatch(s -> s.statType == 5 && s.statValue == 150);
    }

    @Test
    @DisplayName("Default-Inventar: Energy Staff in Slot 0, Rest freie Slots ab 5")
    void defaultInventory() {
        final Player p = player();
        assertThat(p.status().stats).anyMatch(s -> s.statType == 8 && s.statValue == 0xa97);
        assertThat(p.addItem(0x0bad)).as("Slot 5 ist frei").isTrue();
        assertThat(p.status().stats).anyMatch(s -> s.statType == 13 && s.statValue == 0x0bad);
    }

    @Test
    @DisplayName("addItem: volles Inventar schlägt fehl")
    void fullInventory() {
        final Player p = player();
        for (int i = 0; i < 7; i++) {                    // Slots 5–11 frei
            assertThat(p.addItem(i)).as("Slots 5..11").isTrue();
        }
        assertThat(p.addItem(99)).as("kein freier Slot").isFalse();
    }

    @Test
    @DisplayName("Schuss-Spur: gültiger Treffer wird verbraucht")
    void consumeShot() {
        final Player p = player();
        p.registerShot(new Player.Shot(5, 25f, 25f, 17, 2, 0, 475));
        final Player.Shot shot = p.consumeShot(5, 0);
        assertThat(shot).isNotNull();
        assertThat(shot.damage()).isEqualTo(17);
        assertThat(p.consumeShot(5, 0)).as("zweiter Treffer derselben bulletId").isNull();
    }

    @Test
    @DisplayName("bulletId-Wraparound: 255 → 0 deckt beide Projektile ab")
    void bulletIdWrap() {
        final Player p = player();
        p.registerShot(new Player.Shot(255, 25f, 25f, 17, 2, 0, 475));
        assertThat(p.consumeShot(255, 0)).isNotNull();   // Projektil 0 (byte 255)
        assertThat(p.consumeShot(0, 0)).as("255+1 → 0, modulo 256").isNotNull();
    }

    @Test
    @DisplayName("verbrauchte Schuss-Spur: doppelter EnemyHit wird verworfen")
    void doubleEnemyHitRejected() {
        final Player p = player();
        p.registerShot(new Player.Shot(1, 25f, 25f, 17, 1, 0, 475));
        assertThat(p.consumeShot(1, 0)).isNotNull();
        assertThat(p.consumeShot(1, 0)).as("bereits verbraucht").isNull();
    }

    @Test
    @DisplayName("alte Schuss-Spur (nach Lifetime) wird nicht akzeptiert")
    void staleShotRejected() {
        final Player p = player();
        p.registerShot(new Player.Shot(1, 25f, 25f, 17, 1, 0, 475));
        assertThat(p.consumeShot(1, 476)).as("älter als Lifetime 475ms").isNull();
    }
}