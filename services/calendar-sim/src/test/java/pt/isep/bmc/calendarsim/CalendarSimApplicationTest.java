package pt.isep.bmc.calendarsim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CalendarSimApplicationTest {

    @Test
    void helloAnswersUp() {
        assertEquals("calendar-sim up", new CalendarSimApplication().hello());
    }
}
