package io.turnbase.platform.account;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.turnbase.platform.common.PlatformOriginProperties;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * {@code oauth-rest-api.md} §3/§4 — the two calls to Google this item makes: the consent-screen URL
 * and the server-side code exchange. The client secret ({@link OAuthProperties}) is used only here,
 * inside this server-to-server call, and never serialized into anything the browser sees.
 *
 * <p>Identity is read from Google's userinfo endpoint with the freshly-exchanged access token,
 * rather than by verifying the ID token's own signature — the access token itself was just handed
 * to this process directly by Google's token endpoint over TLS, authenticated by the client secret,
 * so a second authenticated call on the same channel needs no separate JWKS verification step to
 * trust its response.
 */
@Component
class GoogleOAuthClient {

  private final OAuthProperties properties;
  private final String redirectUri;
  private final RestClient restClient;

  GoogleOAuthClient(OAuthProperties properties, PlatformOriginProperties platformOrigin) {
    this.properties = properties;
    this.redirectUri = platformOrigin.origin() + "/auth/callback";
    this.restClient = RestClient.create();
  }

  /** {@code secrets-and-configuration.md} §5: the redirect URI Google has registered for us. */
  String redirectUri() {
    return redirectUri;
  }

  /**
   * The URL to send the browser to, carrying the signed {@code state} (§3). Only ever called after
   * {@link OAuthProperties#configured()} has been checked ({@link OAuthService#initiate}), so the
   * client id is guaranteed present despite its {@code @Nullable} type.
   */
  URI authorizationUrl(String state) {
    String query =
        "client_id="
            + encode(Objects.requireNonNull(properties.clientId()))
            + "&redirect_uri="
            + encode(redirectUri)
            + "&response_type=code"
            + "&scope="
            + encode("openid email")
            + "&state="
            + encode(state);
    return URI.create(properties.authorizationEndpoint() + "?" + query);
  }

  /**
   * Exchanges {@code code} for an access token, then resolves the identity it belongs to.
   *
   * @throws GoogleOAuthException the code exchange failed, or the resolved email is unverified —
   *     FR-AUTH-008/016's account-creation and collision logic both trust this email, so an
   *     unverified one must never reach either
   */
  GoogleIdentity exchangeCode(String code) {
    TokenResponse token;
    try {
      MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
      form.add("code", code);
      form.add("client_id", Objects.requireNonNull(properties.clientId()));
      form.add("client_secret", Objects.requireNonNull(properties.clientSecret()));
      form.add("redirect_uri", redirectUri);
      form.add("grant_type", "authorization_code");
      token =
          restClient
              .post()
              .uri(properties.tokenEndpoint())
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .body(form)
              .retrieve()
              .body(TokenResponse.class);
    } catch (RuntimeException e) {
      throw new GoogleOAuthException("Google token exchange failed", e);
    }
    if (token == null) {
      throw new GoogleOAuthException("Google token exchange returned no body", null);
    }

    UserInfoResponse userInfo;
    try {
      userInfo =
          restClient
              .get()
              .uri(properties.userinfoEndpoint())
              .headers(h -> h.setBearerAuth(token.accessToken()))
              .retrieve()
              .body(UserInfoResponse.class);
    } catch (RuntimeException e) {
      throw new GoogleOAuthException("Google userinfo lookup failed", e);
    }
    if (userInfo == null || userInfo.sub() == null || userInfo.email() == null) {
      throw new GoogleOAuthException("Google userinfo response is incomplete", null);
    }
    if (!Boolean.TRUE.equals(userInfo.emailVerified())) {
      throw new GoogleOAuthException("Google account email is not verified", null);
    }
    return new GoogleIdentity(userInfo.sub(), userInfo.email());
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  record GoogleIdentity(String sub, String email) {}

  static class GoogleOAuthException extends RuntimeException {
    GoogleOAuthException(String message, @Nullable Throwable cause) {
      super(message, cause);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record TokenResponse(@JsonProperty("access_token") String accessToken) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record UserInfoResponse(
      String sub, String email, @JsonProperty("email_verified") Boolean emailVerified) {}
}
