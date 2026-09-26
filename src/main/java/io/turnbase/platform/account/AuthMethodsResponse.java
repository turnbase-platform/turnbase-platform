package io.turnbase.platform.account;

import java.util.List;

/** {@code POST /api/users/me/auth-methods/google}'s {@code 200} body — the resulting method set. */
record AuthMethodsResponse(List<String> authMethods) {}
