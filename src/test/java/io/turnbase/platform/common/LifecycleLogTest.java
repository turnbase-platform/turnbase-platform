package io.turnbase.platform.common;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class LifecycleLogTest {

  @Test
  void writesEachPresentFieldAsAKeyValueAndOmitsNullOnes() {
    Logger logger = (Logger) LoggerFactory.getLogger(LifecycleLogTest.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      LifecycleLog.info(logger, "Room left", "roomId", "r1", "userId", null, "reason", "LEFT");
    } finally {
      logger.detachAppender(appender);
    }
    assertThat(appender.list).hasSize(1);
    ILoggingEvent event = appender.list.get(0);
    assertThat(event.getLevel().toString()).isEqualTo("INFO");
    assertThat(event.getFormattedMessage()).isEqualTo("Room left roomId=r1 reason=LEFT");
  }
}
