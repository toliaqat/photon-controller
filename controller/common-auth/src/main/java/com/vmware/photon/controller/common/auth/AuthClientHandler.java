/*
 * Copyright 2015 VMware, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, without warranties or
 * conditions of any kind, EITHER EXPRESS OR IMPLIED.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.vmware.photon.controller.common.auth;

import com.vmware.identity.openidconnect.client.GroupMembershipType;
import com.vmware.identity.openidconnect.client.IDToken;
import com.vmware.identity.openidconnect.client.OIDCClientException;
import com.vmware.identity.openidconnect.client.OIDCTokens;
import com.vmware.identity.openidconnect.client.TokenSpec;
import com.vmware.identity.openidconnect.common.ClientID;
import com.vmware.identity.openidconnect.common.Nonce;
import com.vmware.identity.openidconnect.common.ResponseMode;
import com.vmware.identity.openidconnect.common.ResponseType;
import com.vmware.identity.openidconnect.common.State;
import com.vmware.identity.openidconnect.common.TokenType;
import com.vmware.identity.rest.core.client.exceptions.ClientException;
import com.vmware.identity.rest.idm.client.IdmClient;
import com.vmware.identity.rest.idm.data.OIDCClientDTO;
import com.vmware.identity.rest.idm.data.OIDCClientMetadataDTO;

import org.apache.http.HttpException;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.List;

/**
 * Class that handles oauth clients.
 */
public class AuthClientHandler {
  private static final String LOGIN_STATE = "L";
  private static final String LOGOUT_STATE = "E";
  private static final String LOGIN_REQUEST_NONCE = "1";
  private static final String LOGOUT_URL_ID_TOKEN_START = "id_token_hint";
  private static final String LOGOUT_URL_ID_TOKEN_PLACEHOLDER = "[ID_TOKEN_PLACEHOLDER]";

  private final AuthOIDCClient oidcClient;
  private final String user;
  private final String password;
  private final String tenant;
  private IdmClient idmClient;
  private AuthTokenHandler tokenHandler;

  /**
   * Constructor.
   *
   * @param oidcClient - Provides the oidc client.
   * @param user       - Provides the user of the LightWave Admin.
   * @param password   - Provides the password of the LightWave Admin.
   * @throws AuthException
   * @throws URISyntaxException
   */
  AuthClientHandler(
      AuthOIDCClient oidcClient,
      IdmClient idmClient,
      AuthTokenHandler tokenHandler,
      String user,
      String password,
      String tenant)
      throws AuthException {
    this.oidcClient = oidcClient;
    this.idmClient = idmClient;
    this.tokenHandler = tokenHandler;
    this.user = user;
    this.password = password;
    this.tenant = tenant;
  }

  /**
   * Register implicit client and retrieve login and logout URIs.
   *
   * @param loginRedirectURI
   * @param logoutRedirectURI
   * @return
   * @throws AuthException
   */
  public ImplicitClient registerImplicitClient(URI loginRedirectURI, URI logoutRedirectURI)
      throws AuthException {
    try {
      OIDCTokens tokens = tokenHandler.getAdminServerAccessToken(user, password);
      OIDCClientDTO oidcClientDTO = registerClient(loginRedirectURI, loginRedirectURI, logoutRedirectURI);
      ClientID clientID = new ClientID(oidcClientDTO.getClientId());
      URI loginURI = buildAuthenticationRequestURI(clientID, loginRedirectURI);
      URI logoutURI = buildLogoutRequestURI(clientID, tokens.getIDToken(), logoutRedirectURI);
      return new ImplicitClient(oidcClientDTO.getClientId(), loginURI.toString(), logoutURI.toString());
    } catch (Exception e) {
      throw new AuthException(String.format("Failed to register implicit client with loginRedirectURI %s and " +
          "logoutRedirectURI %s ", loginRedirectURI, logoutRedirectURI), e);
    }
  }

  /**
   * Register OAuth client.
   *
   * @param redirectURI
   * @return
   * @throws AuthException
   */
  public OIDCClientDTO registerClient(URI redirectURI) throws AuthException {
    return registerClient(redirectURI, redirectURI, redirectURI);
  }

  /**
   * Build authentication request URI for the given client. The client is expected to have exactly one redirect URI.
   */
  public URI buildAuthenticationRequestURI(ClientID clientID, URI redirectURI) throws AuthException {
    try {
      TokenSpec tokenSpec = new TokenSpec.Builder(TokenType.BEARER)
          .refreshToken(false)
          .idTokenGroups(GroupMembershipType.NONE)
          .accessTokenGroups(GroupMembershipType.FULL)
          .resourceServers(Arrays.asList(AuthOIDCClient.ResourceServer.rs_esxcloud.toString())).build();

      return oidcClient.getOidcClient(clientID)
          .buildAuthenticationRequestURI(
              redirectURI,
              ResponseType.idTokenAccessToken(),
              ResponseMode.FRAGMENT,
              tokenSpec,
              new State(LOGIN_STATE),
              new Nonce(LOGIN_REQUEST_NONCE));
    } catch (OIDCClientException e) {
      throw new AuthException("Failed to build authentication request URI.", e);
    }
  }

  /**
   * Build logout request URI for the given client. The client is expected to have exactly one post-logout URI.
   */
  private URI buildLogoutRequestURI(ClientID clientID, IDToken idToken, URI postLogoutURI) throws AuthException,
      URISyntaxException, UnsupportedEncodingException {
    try {
      return replaceIdTokenWithPlaceholder(oidcClient.getOidcClient(clientID)
          .buildLogoutRequestURI(postLogoutURI, idToken, new State(LOGOUT_STATE)));
    } catch (OIDCClientException e) {
      throw new AuthException("Failed to build logout URI", e);
    }
  }

  /**
   * Register OAuth client.
   */
  private OIDCClientDTO registerClient(URI redirectURI, URI logoutURI, URI postLogoutURI)
      throws AuthException {
    Exception registerException = null;

    try {
      // register an OIDC client
      OIDCClientMetadataDTO oidcClientMetadataDTO = new OIDCClientMetadataDTO.Builder()
          .withRedirectUris(Arrays.asList(redirectURI.toString()))
          .withPostLogoutRedirectUris(Arrays.asList(postLogoutURI.toString()))
          .withLogoutUri(logoutURI.toString())
          .withTokenEndpointAuthMethod("none")
          .build();
      idmClient.oidcClient().register(tenant, oidcClientMetadataDTO);

    } catch (ClientException | HttpException | IOException e) {
      registerException = new AuthException("failed to registerClient", e);
    }

    try {
      List<OIDCClientDTO> oidcClientDTOList = idmClient.oidcClient().getAll(tenant);
      for (OIDCClientDTO oidcClientDTO : oidcClientDTOList) {
        if (oidcClientDTO.getOIDCClientMetadataDTO().getRedirectUris().size() == 1 &&
            oidcClientDTO.getOIDCClientMetadataDTO().getRedirectUris().iterator().next()
                .compareTo(redirectURI.toString()) == 0) {
          return oidcClientDTO;
        }
      }
    } catch (ClientException | HttpException | IOException e) {
      throw new AuthException("failed to registerClient", e);
    }

    throw new AuthException("Client expected to be registered,  but not found", registerException);
  }

  /**
   * Replace the id_token part in logout URL with placeholder.
   *
   * @param logoutUri
   * @throws AuthException
   */
  public static URI replaceIdTokenWithPlaceholder(URI logoutUri)
          throws IllegalArgumentException, URISyntaxException, UnsupportedEncodingException{
    String placeholderValue = "";
    String keyToMatch = URLDecoder.decode(LOGOUT_URL_ID_TOKEN_START, "UTF-8");
    final String urlQuery = logoutUri.getQuery();

    if (urlQuery == null) {
      throw new IllegalArgumentException(String.format("Logout URL %s has invalid format", logoutUri.toString()));
    }

    final String[] queryParams = urlQuery.split("&");

    for (String param : queryParams) {
      final String[] pairs = param.split("=");
      if (pairs[0].equals(keyToMatch)) {
        placeholderValue = pairs[1];
        break;
      }
    }

    if (placeholderValue == "") {
      throw new IllegalArgumentException(String.format("Logout URL %s has invalid format", logoutUri.toString()));
    }

    String logoutStr = logoutUri.toString();
    return new URI(logoutStr.replace(placeholderValue, LOGOUT_URL_ID_TOKEN_PLACEHOLDER));
  }

  /**
   * Class encapsulating implicit client info.
   */
  public static class ImplicitClient {
    public final String clientID;
    public final String loginURI;
    public final String logoutURI;

    public ImplicitClient(String clientID, String loginURI, String logoutURI) {
      this.clientID = clientID;
      this.loginURI = loginURI;
      this.logoutURI = logoutURI;
    }
  }
}
