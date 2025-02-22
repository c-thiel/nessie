/*
 * Copyright (C) 2023 Dremio
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.projectnessie.client.auth.oauth2;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.time.Instant;
import java.util.Map;
import javax.annotation.Nullable;
import org.immutables.value.Value;

/**
 * Common base for {@link ClientCredentialsTokensResponse}, {@link RefreshTokensResponse} and {@link
 * TokensExchangeResponse}, since they share most of their schema, which is declared in <a
 * href="https://datatracker.ietf.org/doc/html/rfc6749#section-5.1">Section 5.1</a>.
 *
 * <p>It is also a flattened representation of a {@link Tokens} pair.
 */
interface TokensResponseBase extends Tokens {

  @Value.Lazy
  @JsonIgnore
  @Override
  default AccessToken getAccessToken() {
    return ImmutableAccessToken.builder()
        .tokenType(getTokenType())
        .payload(getAccessTokenPayload())
        .expirationTime(getAccessTokenExpirationTime())
        .build();
  }

  @Nullable
  @Value.Lazy
  @JsonIgnore
  @Override
  default RefreshToken getRefreshToken() {
    if (getRefreshTokenPayload() != null) {
      return ImmutableRefreshToken.builder()
          .payload(getRefreshTokenPayload())
          .expirationTime(getRefreshTokenExpirationTime())
          .build();
    }
    return null;
  }

  /**
   * REQUIRED. The type of the token issued as described in <a
   * href="https://datatracker.ietf.org/doc/html/rfc6749#section-7.1">Section 7.1</a>. Value is
   * case-insensitive.
   *
   * <p>This is typically "Bearer".
   */
  @JsonProperty("token_type")
  String getTokenType();

  /** REQUIRED. The access token issued by the authorization server. */
  @JsonProperty("access_token")
  String getAccessTokenPayload();

  /**
   * RECOMMENDED. The lifetime in seconds of the access token. For example, the value "3600" denotes
   * that the access token will expire in one hour from the time the response was generated. If
   * omitted, the authorization server SHOULD provide the expiration time via other means or
   * document the default value.
   */
  @Nullable
  @JsonProperty("expires_in")
  @JsonUnwrapped
  @JsonSerialize(using = JacksonSerializers.InstantToSecondsSerializer.class)
  @JsonDeserialize(using = JacksonSerializers.SecondsToInstantDeserializer.class)
  Instant getAccessTokenExpirationTime();

  /**
   * OPTIONAL. The refresh token, which can be used to obtain new access tokens using the same
   * authorization grant as described in <a
   * href="https://datatracker.ietf.org/doc/html/rfc6749#section-6">Section 6</a>.
   *
   * <p>Note: in the client credentials flow (grant type {@value
   * ClientCredentialsTokensRequest#GRANT_TYPE}), as per <a
   * href="https://datatracker.ietf.org/doc/html/rfc6749#section-4.4.3">Section 4.4.3</a>, "A
   * refresh token SHOULD NOT be included". Keycloak indeed does not include a refresh token in the
   * response to a client credentials flow, unless the client is configured with the attribute
   * "client_credentials.use_refresh_token" set to "true".
   */
  @Nullable
  @JsonProperty("refresh_token")
  String getRefreshTokenPayload();

  /**
   * Not in the OAuth2 spec, but used by Keycloak. The lifetime in seconds of the refresh token,
   * when a refresh token is included in the response.
   */
  @Nullable
  @JsonProperty("refresh_expires_in")
  @JsonSerialize(using = JacksonSerializers.InstantToSecondsSerializer.class)
  @JsonDeserialize(using = JacksonSerializers.SecondsToInstantDeserializer.class)
  Instant getRefreshTokenExpirationTime();

  /**
   * OPTIONAL, if identical to the scope requested by the client; otherwise, REQUIRED. The scope of
   * the access token as described by <a
   * href="https://datatracker.ietf.org/doc/html/rfc6749#section-3.3">Section 3.3</a>.
   */
  @Nullable
  @JsonProperty("scope")
  String getScope();

  @JsonAnyGetter
  Map<String, Object> getExtraParameters();
}
