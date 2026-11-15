package pt.isep.bmc.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DispatchApplicationTest {

    @Test
    void helloAnswersUp() {
        assertEquals("dispatch up", new DispatchApplication().hello());
    }
}
