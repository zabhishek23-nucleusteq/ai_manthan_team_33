package com.expenseflow.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Seeds synthetic data on start when the "seed" profile is active (docker-compose sets SPRING_PROFILES_ACTIVE=seed). */
@Component
@Profile("seed")
public class SeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);

    private final SeedService seed;
    private final SeedSummary summary;

    public SeedRunner(SeedService seed, SeedSummary summary) {
        this.seed = seed;
        this.summary = summary;
    }

    @Override
    public void run(ApplicationArguments args) {
        SeedService.SeedStats stats = seed.seedIfEmpty();
        if (stats.seeded()) {
            log.info("Seed summary:\n{}", summary.markdown(stats.millis()));
        }
    }
}
