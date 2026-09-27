package dev.localsoul.aero.actor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.localsoul.aero.actor.internal.Envelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("LinkedMailbox")
class LinkedMailboxTest {

    private static Envelope env(int i) {
        return Envelope.user(i, null);
    }

    @Test
    @DisplayName("size() zaehlt die wartenden Nachrichten, nicht alle je angenommenen")
    void sizeReflectsCurrentBacklog() {
        LinkedMailbox mailbox = new LinkedMailbox();
        for (int i = 0; i < 3; i++) {
            assertThat(mailbox.offer(env(i))).isTrue();
        }
        assertThat(mailbox.size()).as("3 angeboten").isEqualTo(3);

        assertThat(mailbox.poll()).isNotNull();
        assertThat(mailbox.poll()).isNotNull();
        assertThat(mailbox.size()).as("nach dem Konsum wieder kleiner").isEqualTo(1);

        assertThat(mailbox.poll()).isNotNull();
        assertThat(mailbox.isEmpty()).isTrue();
        assertThat(mailbox.size()).isZero();
    }

    @Test
    @DisplayName("unbounded: offer wirft nie, auch nach close nicht")
    void unboundedOfferNeverThrows() {
        LinkedMailbox mailbox = new LinkedMailbox();
        mailbox.close();
        assertThat(mailbox.offer(env(1))).isFalse();
        assertThat(mailbox.rejected()).isEqualTo(1);
    }
}