package io.turnbase.platform.history;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OutcomeLabelTest {

  @Test
  void drawWinsOverPlacement() {
    assertThat(OutcomeLabel.of(true, 2, 4)).isEqualTo("Draw");
  }

  @Test
  void firstPlaceIsVictoryAndSecondOfTwoIsDefeat() {
    assertThat(OutcomeLabel.of(false, 1, 2)).isEqualTo("Victory");
    assertThat(OutcomeLabel.of(false, 2, 2)).isEqualTo("Defeat");
  }

  @Test
  void moreThanTwoTeamsUseOrdinals() {
    assertThat(OutcomeLabel.of(false, 2, 4)).isEqualTo("2nd place");
    assertThat(OutcomeLabel.of(false, 3, 4)).isEqualTo("3rd place");
    assertThat(OutcomeLabel.of(false, 4, 4)).isEqualTo("4th place");
  }

  @Test
  void elevenTwelveThirteenTakeTheThSuffix() {
    assertThat(OutcomeLabel.ordinal(11)).isEqualTo("11th");
    assertThat(OutcomeLabel.ordinal(12)).isEqualTo("12th");
    assertThat(OutcomeLabel.ordinal(13)).isEqualTo("13th");
    assertThat(OutcomeLabel.ordinal(21)).isEqualTo("21st");
    assertThat(OutcomeLabel.ordinal(112)).isEqualTo("112th");
  }
}
