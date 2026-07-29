package com.beamcard.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.status.Status;
import java.io.File;
import org.junit.jupiter.api.Test;

class LokiLogbackConfigTest {

    @Test
    void lokiAppenderConfiguresWithoutErrors() throws Exception {
        LoggerContext context = new LoggerContext();
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure(new File("src/test/resources/logback-loki-verify.xml"));

        boolean hasErrors = context.getStatusManager().getCopyOfStatusList().stream()
                .anyMatch(s -> s.getLevel() == Status.ERROR);
        assertThat(hasErrors)
                .as("Loki appender config should parse and start without errors")
                .isFalse();

        assertThat(context.getLogger("ROOT").getAppender("LOKI"))
                .as("Loki4jAppender should be wired")
                .isNotNull();
        context.stop();
    }
}
