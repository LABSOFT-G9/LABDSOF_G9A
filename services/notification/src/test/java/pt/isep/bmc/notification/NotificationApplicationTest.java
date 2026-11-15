package pt.isep.bmc.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NotificationApplicationTest {

    @Test
    void helloAnswersUp() {
        assertEquals("notification up", new NotificationApplication().hello());
    }
}
