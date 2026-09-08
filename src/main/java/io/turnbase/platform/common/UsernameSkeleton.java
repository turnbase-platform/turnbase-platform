package io.turnbase.platform.common;

import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.text.SpoofChecker;

/**
 * {@code input-validation.md} § Usernames: {@code getSkeleton(caseFold(NFC(username)))} — the
 * confusable-skeleton uniqueness key {@code data-model-accounts.md} §1 stores as {@code
 * app_user.username_skeleton}. Lives in {@code common}, not {@code account}, so a second feature
 * resolving a username to an account ({@code social}, for a friend request) computes the identical
 * key rather than re-implementing the algorithm and risking drift from registration's.
 */
public final class UsernameSkeleton {

  private static final SpoofChecker SPOOF_CHECKER = new SpoofChecker.Builder().build();

  private UsernameSkeleton() {}

  /** {@code canonical} must already be {@link Canonicalizer#canonicalize} output. */
  public static String of(String canonical) {
    String caseFolded = UCharacter.foldCase(canonical, true);
    return SPOOF_CHECKER.getSkeleton(caseFolded);
  }
}
