package pt.isep.bmc.calendarsim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
public class CalendarSimApplication {

    public static void main(String[] args) {
        SpringApplication.run(CalendarSimApplication.class, args);
    }

    @GetMapping("/")
    public String hello() {
        return "calendar-sim up";
    }
}
