/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.am.gateway.handler.vertx.handler.oauth2.endpoint.authorization;

import io.gravitee.am.gateway.handler.oauth2.code.AuthorizationCodeService;
import io.gravitee.am.gateway.handler.oauth2.exception.InvalidRequestException;
import io.gravitee.am.gateway.handler.oauth2.granter.TokenGranter;
import io.gravitee.am.gateway.handler.oauth2.request.AuthorizationRequest;
import io.gravitee.am.gateway.handler.oauth2.request.TokenRequest;
import io.gravitee.am.gateway.handler.oauth2.response.AuthorizationCodeResponse;
import io.gravitee.am.gateway.handler.oauth2.response.ImplicitResponse;
import io.gravitee.am.gateway.handler.oauth2.token.AccessToken;
import io.gravitee.am.gateway.handler.oauth2.utils.OAuth2Constants;
import io.gravitee.am.gateway.handler.utils.UriBuilder;
import io.gravitee.am.gateway.handler.vertx.handler.oauth2.request.TokenRequestFactory;
import io.gravitee.am.model.Client;
import io.gravitee.am.model.User;
import io.reactivex.Single;
import io.vertx.core.Handler;
import io.vertx.reactivex.ext.web.RoutingContext;

import java.net.URISyntaxException;

/**
 * @author Titouan COMPIEGNE (titouan.compiegne at graviteesource.com)
 * @author GraviteeSource Team
 */
public abstract class AbstractAuthorizationEndpointHandler implements Handler<RoutingContext> {

    private final TokenRequestFactory tokenRequestFactory = new TokenRequestFactory();
    private AuthorizationCodeService authorizationCodeService;
    private TokenGranter tokenGranter;

    public AbstractAuthorizationEndpointHandler(AuthorizationCodeService authorizationCodeService,
                                        TokenGranter tokenGranter) {
        this.authorizationCodeService = authorizationCodeService;
        this.tokenGranter = tokenGranter;
    }

    protected Single<AuthorizationRequest> createAuthorizationResponse(AuthorizationRequest authorizationRequest, Client client, User authenticatedUser) {
        // request is not approved, the user will be redirect to the approval page
        if (!authorizationRequest.isApproved()) {
            return Single.just(authorizationRequest);
        }

        // handle response type
        switch(authorizationRequest.getResponseType()) {
            case OAuth2Constants.TOKEN :
                return setImplicitResponse(authorizationRequest, client);
            case OAuth2Constants.CODE :
                return setAuthorizationCodeResponse(authorizationRequest, authenticatedUser);
            default:
                return Single.just(authorizationRequest);
        }
    }

    protected String buildRedirectUri(AuthorizationRequest authorizationRequest) throws Exception {
        String responseType = authorizationRequest.getResponseType();
        switch (responseType) {
            case OAuth2Constants.TOKEN:
                return buildImplicitGrantRedirectUri(authorizationRequest);
            case OAuth2Constants.CODE:
                return buildAuthorizationCodeRedirectUri(authorizationRequest);
            default:
                throw new InvalidRequestException("Invalid response type : " + responseType);

        }
    }

    private String buildImplicitGrantRedirectUri(AuthorizationRequest authorizationRequest) throws URISyntaxException {
        ImplicitResponse authorizationResponse = (ImplicitResponse) authorizationRequest.getResponse();
        AccessToken accessToken = authorizationResponse.getAccessToken();
        UriBuilder uriBuilder = UriBuilder.fromURIString(authorizationRequest.getRedirectUri());
        uriBuilder.addFragmentParameter(AccessToken.ACCESS_TOKEN, accessToken.getValue());
        uriBuilder.addFragmentParameter(AccessToken.TOKEN_TYPE, accessToken.getTokenType());
        uriBuilder.addFragmentParameter(AccessToken.EXPIRES_IN, String.valueOf(accessToken.getExpiresIn()));
        if (accessToken.getScope() != null && !accessToken.getScope().isEmpty()) {
            uriBuilder.addFragmentParameter(AccessToken.SCOPE, accessToken.getScope());
        }
        if (authorizationResponse.getState() != null) {
            uriBuilder.addFragmentParameter(OAuth2Constants.STATE, authorizationRequest.getState());
        }
        return uriBuilder.build().toString();
    }

    private String buildAuthorizationCodeRedirectUri(AuthorizationRequest authorizationRequest) throws URISyntaxException {
        AuthorizationCodeResponse authorizationResponse = (AuthorizationCodeResponse) authorizationRequest.getResponse();
        UriBuilder uriBuilder = UriBuilder.fromURIString(authorizationRequest.getRedirectUri());
        uriBuilder.addParameter(OAuth2Constants.CODE, authorizationResponse.getCode());
        if (authorizationResponse.getState() != null) {
            uriBuilder.addParameter(OAuth2Constants.STATE, authorizationRequest.getState());
        }
        return uriBuilder.build().toString();
    }

    private Single<AuthorizationRequest> setAuthorizationCodeResponse(AuthorizationRequest authorizationRequest, User authenticatedUser) {
        // prepare response
        return authorizationCodeService.create(authorizationRequest, authenticatedUser)
                .map(code -> {
                    AuthorizationCodeResponse response = new AuthorizationCodeResponse();
                    response.setCode(code.getCode());
                    response.setState(authorizationRequest.getState());
                    authorizationRequest.setResponse(response);
                    return authorizationRequest;
                });
    }

    private Single<AuthorizationRequest> setImplicitResponse(AuthorizationRequest authorizationRequest, Client client) {
        TokenRequest tokenRequest = tokenRequestFactory.create(authorizationRequest);
        tokenRequest.setGrantType(OAuth2Constants.IMPLICIT);
        return tokenGranter.grant(tokenRequest, client)
                .map(accessToken -> {
                    ImplicitResponse response = new ImplicitResponse();
                    response.setAccessToken(accessToken);
                    response.setState(authorizationRequest.getState());
                    authorizationRequest.setResponse(response);
                    return authorizationRequest;
                });
    }
}
