package vn.tuanthongma.ieltsreading;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class IeltsReadingApplication {

    public static void main(String[] args) {
        SpringApplication.run(IeltsReadingApplication.class, args);
    }
}
