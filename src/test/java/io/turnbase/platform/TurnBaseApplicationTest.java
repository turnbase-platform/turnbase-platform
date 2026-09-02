package io.turnbase.platform;

import org.junit.jupiter.api.Test;

/** Boots the real application through its own {@code main}, on ephemeral ports. */
class TurnBaseApplicationTest {

  @Test
  void mainStartsTheApplication() {
    TurnBaseApplication.main(new String[] {"--server.port=0", "--management.server.port=0"});
  }
}
