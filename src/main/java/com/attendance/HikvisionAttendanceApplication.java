package com.attendance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
@EnableAsync
public class HikvisionAttendanceApplication {
    public static void main(String[] args) {
        // Pin the JVM default time zone so camera event times (reported with an offset,
        // e.g. 2026-11-23T14:59:14+03:00) render in local wall-clock time instead of UTC.
        // Every ZoneId.systemDefault()/LocalDateTime.now() in the app then stays consistent.
        // Override with -Dapp.timezone=... or the APP_TIMEZONE environment variable.
        String tz = System.getProperty("app.timezone",
                System.getenv().getOrDefault("APP_TIMEZONE", "Asia/Baghdad"));
        TimeZone.setDefault(TimeZone.getTimeZone(tz));

        SpringApplication.run(HikvisionAttendanceApplication.class, args);
    }
}
