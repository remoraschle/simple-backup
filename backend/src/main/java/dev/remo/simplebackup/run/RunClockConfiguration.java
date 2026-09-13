package dev.remo.simplebackup.run;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class RunClockConfiguration {

    /**
     * Die Uhr als Bean.
     *
     * <p>Damit laesst sich der Totmannschalter pruefen, ohne einen Test tagelang warten zu
     * lassen: Er bekommt eine feste Uhr und man sieht zu, was er tut.
     */
    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }
}
