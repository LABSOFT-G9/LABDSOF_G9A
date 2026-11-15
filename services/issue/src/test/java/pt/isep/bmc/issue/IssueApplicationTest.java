package pt.isep.bmc.issue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class IssueApplicationTest {

    @Test
    void helloAnswersUp() {
        assertEquals("issue up", new IssueApplication().hello());
    }
}
