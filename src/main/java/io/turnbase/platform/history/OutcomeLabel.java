package io.turnbase.platform.history;

/** FR-HIST-010…013: the label is derived at read time from three stored columns. */
final class OutcomeLabel {

  private OutcomeLabel() {}

  static String of(boolean draw, int placement, int teamCount) {
    if (draw) {
      return "Draw";
    }
    if (placement == 1) {
      return "Victory";
    }
    if (teamCount == 2) {
      return "Defeat";
    }
    return ordinal(placement) + " place";
  }

  static String ordinal(int n) {
    int lastTwo = n % 100;
    if (lastTwo >= 11 && lastTwo <= 13) {
      return n + "th";
    }
    return n
        + switch (n % 10) {
          case 1 -> "st";
          case 2 -> "nd";
          case 3 -> "rd";
          default -> "th";
        };
  }
}
