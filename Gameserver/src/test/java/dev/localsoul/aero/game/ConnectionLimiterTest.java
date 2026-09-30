package dev.localsoul.aero.game;

import static org.assertj.core.api.Assertions.assertThat;

import dev.localsoul.aero.game.net.ConnectionLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ConnectionLimiter")
class ConnectionLimiterTest {

    @Test
    @DisplayName("Limit pro IP wird durchgesetzt")
    void enforcesPerIpLimit() {
        final ConnectionLimiter limiter = new ConnectionLimiter(2);
        assertThat(limiter.acquire("1.2.3.4")).isTrue();
        assertThat(limiter.acquire("1.2.3.4")).isTrue();
        assertThat(limiter.acquire("1.2.3.4")).as("dritte überschreitet das Limit").isFalse();
        assertThat(limiter.acquire("5.6.7.8")).as("andere IP unberührt").isTrue();
    }

    @Test
    @DisplayName("Release auf jedem Exit-Pfad: Zähler kehrt auf 0 zurück (kein Leak)")
    void releaseOnEveryExitReturnsToZero() {
        final ConnectionLimiter limiter = new ConnectionLimiter(3);
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.acquire("10.0.0.1")).isTrue();
        }
        assertThat(limiter.activeCount()).isEqualTo(3);

        // Pfade: normaler Disconnect, exceptionCaught, Kick — alle rufen release.
        limiter.release("10.0.0.1");
        limiter.release("10.0.0.1");
        limiter.release("10.0.0.1");
        assertThat(limiter.activeCount()).isZero();
        assertThat(limiter.acquire("10.0.0.1")).as("IP wieder frei").isTrue();
    }

    @Test
    @DisplayName("abgelehnte Verbindung inkrementiert den Zähler nicht")
    void rejectedConnectionDoesNotLeak() {
        final ConnectionLimiter limiter = new ConnectionLimiter(1);
        assertThat(limiter.acquire("10.0.0.2")).isTrue();
        assertThat(limiter.acquire("10.0.0.2")).isFalse();   // abgelehnt: kein Zähler
        assertThat(limiter.activeCount()).isEqualTo(1);

        limiter.release("10.0.0.2");                          // nur der einen akzeptierten
        assertThat(limiter.activeCount()).isZero();
    }
}