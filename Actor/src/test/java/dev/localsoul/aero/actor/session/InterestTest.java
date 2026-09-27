package dev.localsoul.aero.actor.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.localsoul.aero.actor.ActorRef;
import dev.localsoul.aero.actor.TestRefs;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InterestTest {

    @Test
    @DisplayName("der Konstruktor weist negative Radien und NaN ab")
    void rejectsBadRadius() {
        assertThatThrownBy(() -> Interest.at(0, 0, 0, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("radius");
        assertThatThrownBy(() -> Interest.at(0, 0, 0, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Interest.at(Double.NaN, 0, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Interest.at(0, 0, 0, 0)).isNotNull();
    }

    @Test
    @DisplayName("UNBOUNDED gilt als unbegrenzt und enthaelt alles")
    void unboundedContainsEverything() {
        assertThat(Interest.UNBOUNDED.isUnbounded()).isTrue();
        assertThat(Interest.UNBOUNDED.contains(1e300, -1e300, 42)).isTrue();
        assertThat(Interest.at(0, 0, 0, 5).isUnbounded()).isFalse();
    }

    @Test
    @DisplayName("contains prueft die Kugel exakt, inklusive Rand")
    void containsIsExact() {
        Interest area = Interest.at(0, 0, 0, 10);
        assertThat(area.contains(0, 0, 0)).isTrue();
        assertThat(area.contains(10, 0, 0)).as("Rand ist drin").isTrue();
        assertThat(area.contains(10.001, 0, 0)).isFalse();
        assertThat(area.contains(0, 10, 0)).isTrue();
        assertThat(area.contains(0, 0, 10)).isTrue();
        assertThat(area.contains(6, 6, 6)).as("Diagonale passt nicht").isFalse();
        assertThat(area.contains(0, 0, 8)).isTrue();
    }

    @Test
    @DisplayName("contains(Interest) prueft das Zentrum, nicht die Ueberlappung")
    void containsUsesCentre() {
        Interest area = Interest.at(0, 0, 0, 10);
        assertThat(area.contains(Interest.point(5, 0, 0))).isTrue();
        assertThat(area.contains(Interest.point(20, 0, 0))).isFalse();
        // Zwei Kugeln, die sich barely schneiden, aber deren Zentren weit auseinander liegen.
        assertThat(Interest.at(0, 0, 0, 10).overlaps(Interest.at(19, 0, 0, 10))).isTrue();
        assertThat(area.contains(Interest.at(19, 0, 0, 10))).as("Zentrum zu weit").isFalse();
    }

    @Test
    @DisplayName("UNBOUNDED enthaelt UNBOUNDED, aber kein begrenztes Interesse")
    void unboundedSemantics() {
        assertThat(Interest.UNBOUNDED.contains(Interest.UNBOUNDED)).isTrue();
        assertThat(Interest.UNBOUNDED.contains(Interest.point(1e6, 1e6, 1e6))).isFalse();
        assertThat(Interest.at(0, 0, 0, 5).contains(Interest.UNBOUNDED)).isFalse();
    }

    @Test
    @DisplayName("contains toleriert null")
    void containsNull() {
        assertThat(Interest.at(0, 0, 0, 5).contains((Interest) null)).isFalse();
    }

    @Test
    @DisplayName("Abstandsrechnung ohne sqrt im heissen Pfad")
    void distances() {
        Interest area = Interest.at(1, 2, 2, 3);
        assertThat(area.squaredDistance(1, 2, 2)).isZero();
        assertThat(area.distanceTo(1, 2, 2)).isZero();
        assertThat(area.distanceTo(4, 2, 2)).isEqualTo(3.0);
    }

    @Test
    @DisplayName("das AABB umschliesst die Kugel")
    void aabbEnclosesSphere() {
        Interest area = Interest.at(5, -5, 5, 2);
        Interest.Aabb box = area.aabb();
        assertThat(box.minX()).isEqualTo(3.0);
        assertThat(box.maxZ()).isEqualTo(7.0);
        assertThat(box.contains(5, -5, 5)).isTrue();
        assertThat(box.contains(2, -5, 5)).isFalse();
    }

    @Test
    @DisplayName("movedTo aendert nur die Position")
    void movedTo() {
        Interest area = Interest.at(1, 1, 1, 4);
        Interest moved = area.movedTo(9, 9, 9);
        assertThat(moved.x()).isEqualTo(9.0);
        assertThat(moved.radius()).isEqualTo(4.0);
        assertThat(area.x()).as("unveraendert").isEqualTo(1.0);
    }

    @Test
    @DisplayName("withRadius aendert nur den Radius")
    void withRadius() {
        Interest area = Interest.at(1, 1, 1, 4).withRadius(100);
        assertThat(area.radius()).isEqualTo(100.0);
        assertThat(area.x()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Fabriken fuer Punkt und Kugel")
    void factories() {
        assertThat(Interest.point(2, 3, 4).radius()).isZero();
        assertThat(Interest.at(2, 3, 4, 5)).isEqualTo(new Interest(2, 3, 4, 5));
    }

    @Test
    @DisplayName("InterestSet liefert nur exakt passende Refs")
    void interestSetExactness() {
        InterestSet set = InterestSet.forRadius(10);
        ActorRef a = new TestRefs.FakeRef("a");
        ActorRef b = new TestRefs.FakeRef("b");
        set.put(a, Interest.point(0, 0, 0));
        set.put(b, Interest.point(100, 0, 0));

        List<ActorRef> out = new ArrayList<>();
        assertThat(set.near(Interest.at(0, 0, 0, 5), out)).isEqualTo(1);
        assertThat(out).containsExactly(a);
    }

    @Test
    @DisplayName("negative Koordinaten landen im richtigen Bucket")
    void negativeCoordinates() {
        InterestSet set = InterestSet.forRadius(4);
        ActorRef a = new TestRefs.FakeRef("a");
        ActorRef b = new TestRefs.FakeRef("b");
        set.put(a, Interest.point(-100, -100, -100));
        set.put(b, Interest.point(-105, -100, -100));
        assertThat(set.near(Interest.at(-100, -100, -100, 1))).containsExactly(a);
        assertThat(set.near(Interest.at(-105, -100, -100, 1))).containsExactly(b);
        assertThat(set.near(Interest.at(-102, -100, -100, 1))).as("dazwischen: keiner")
                .isEmpty();
    }

    @Test
    @DisplayName("UNBOUNDED-Abfrage liefert alle, ohne Duplikate")
    void unboundedReturnsAll() {
        InterestSet set = InterestSet.forRadius(10);
        ActorRef a = new TestRefs.FakeRef("a");
        ActorRef b = new TestRefs.FakeRef("b");
        ActorRef c = new TestRefs.FakeRef("c");
        set.put(a, Interest.point(0, 0, 0));
        set.put(b, Interest.UNBOUNDED);
        set.put(c, Interest.point(500, 500, 500));

        List<ActorRef> out = new ArrayList<>();
        assertThat(set.near(Interest.UNBOUNDED, out)).isEqualTo(3);
        assertThat(out).hasSize(3).doesNotHaveDuplicates();
        assertThat(set.globals()).containsExactly(b);
    }

    @Test
    @DisplayName("null in near liefert 0 Treffer")
    void nullArea() {
        InterestSet set = InterestSet.forRadius(10);
        assertThat(set.near(null)).isEmpty();
    }

    @Test
    @DisplayName("put ersetzt, move verlangt Bekanntes, remove ist idempotent")
    void mutationSemantics() {
        InterestSet set = InterestSet.forRadius(10);
        ActorRef a = new TestRefs.FakeRef("a");
        set.put(a, Interest.point(0, 0, 0));
        set.put(a, Interest.point(100, 100, 100));
        assertThat(set.size()).isEqualTo(1);
        assertThat(set.areaOf(a).x()).isEqualTo(100.0);

        set.move(a, Interest.point(0, 0, 0));
        assertThat(set.areaOf(a).x()).isZero();

        assertThatThrownBy(() -> set.move(new TestRefs.FakeRef("x"), Interest.point(1, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class);

        set.remove(a);
        set.remove(a);
        assertThat(set.size()).isZero();
        assertThat(set.contains(a)).isFalse();
    }

    @Test
    @DisplayName("clear leert alles, auch die globalen Interessen")
    void clearEmpties() {
        InterestSet set = InterestSet.forRadius(10);
        set.put(new TestRefs.FakeRef("a"), Interest.UNBOUNDED);
        set.put(new TestRefs.FakeRef("b"), Interest.point(1, 1, 1));
        set.clear();
        assertThat(set.size()).isZero();
        assertThat(set.globals()).isEmpty();
        assertThat(set.near(Interest.UNBOUNDED)).isEmpty();
    }

    @Test
    @DisplayName("null in put wird abgewiesen")
    void putRejectsNull() {
        InterestSet set = InterestSet.forRadius(10);
        assertThatThrownBy(() -> set.put(null, Interest.UNBOUNDED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> set.put(new TestRefs.FakeRef("a"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("2D- und 3D-Modus werden unterschieden")
    void dimensions() {
        InterestSet flat = new InterestSet(8, 2);
        assertThat(flat.dimension()).isEqualTo(2);
        assertThat(flat.cellSize()).isEqualTo(8.0);
        flat.put(new TestRefs.FakeRef("a"), Interest.point(0, 0, 0));
        assertThat(flat.size()).isEqualTo(1);
        assertThat(flat.cellCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("ungueltige Zellgroessen und Dimensionen fliegen raus")
    void rejectsBadConfig() {
        assertThatThrownBy(() -> new InterestSet(0, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InterestSet(-1, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InterestSet(1, 4))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InterestSet.forRadius(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("500 Spieler: die near()-Abfrage bleibt exakt und brauchbar")
    void manyPlayers() {
        InterestSet set = InterestSet.forRadius(20);
        List<ActorRef> all = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            ActorRef ref = new TestRefs.FakeRef("p" + i);
            all.add(ref);
            set.put(ref, Interest.point(i % 25 * 10, i / 25 * 10, 0));
        }
        // 6 der 500 Punkte liegen im 20er-Radius um (0,0,0)
        List<ActorRef> near = set.near(Interest.at(0, 0, 0, 20));
        assertThat(near).hasSize(6);
        assertThat(near).doesNotHaveDuplicates();
        assertThat(set.cellCount()).as("das verteilt die Spieler ueber Buckets")
                .isGreaterThan(1);
    }

    @Test
    @DisplayName("out wird vom Aufrufer wiederverwendet und vorher geleert")
    void outIsReused() {
        InterestSet set = InterestSet.forRadius(10);
        ActorRef a = new TestRefs.FakeRef("a");
        set.put(a, Interest.point(0, 0, 0));
        List<ActorRef> out = new ArrayList<>();
        set.near(Interest.at(0, 0, 0, 5), out);
        out.add(new TestRefs.FakeRef("dirty"));
        set.near(Interest.at(0, 0, 0, 5), out);
        assertThat(out).containsExactly(a);
    }
}
