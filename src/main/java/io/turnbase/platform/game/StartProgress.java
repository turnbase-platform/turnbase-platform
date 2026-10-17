package io.turnbase.platform.game;

/** Receives each {@link StartPhase} as session creation enters it. Must not throw. */
@FunctionalInterface
public interface StartProgress {

  StartProgress NONE = phase -> {};

  void report(StartPhase phase);
}
