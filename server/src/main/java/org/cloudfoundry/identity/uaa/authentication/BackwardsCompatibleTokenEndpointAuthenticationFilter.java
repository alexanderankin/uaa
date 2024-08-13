/*
 * *****************************************************************************
 *     Cloud Foundry
 *     Copyright (c) [2009-2016] Pivotal Software, Inc. All Rights Reserved.
 *
 *     This product is licensed to you under the Apache License, Version 2.0 (the "License").
 *     You may not use this product except in compliance with the License.
 *
 *     This product includes a number of subcomponents with
 *     separate copyright notices and license terms. Your use of these
 *     subcomponents is subject to the terms and conditions of the
 *     subcomponent's license, as noted in the LICENSE file.
 *******************************************************************************/

package org.cloudfoundry.identity.uaa.authentication;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.cloudfoundry.identity.uaa.oauth.common.exceptions.OAuth2Exception;
import org.cloudfoundry.identity.uaa.oauth.common.util.OAuth2Utils;
import org.cloudfoundry.identity.uaa.oauth.provider.AuthorizationRequest;
import org.cloudfoundry.identity.uaa.oauth.provider.OAuth2Authentication;
import org.cloudfoundry.identity.uaa.oauth.provider.OAuth2Request;
import org.cloudfoundry.identity.uaa.oauth.provider.OAuth2RequestFactory;
import org.cloudfoundry.identity.uaa.oauth.provider.error.OAuth2AuthenticationEntryPoint;
import org.cloudfoundry.identity.uaa.oauth.token.ClaimConstants;
import org.cloudfoundry.identity.uaa.provider.oauth.ExternalOAuthAuthenticationManager;
import org.cloudfoundry.identity.uaa.provider.oauth.ExternalOAuthCodeToken;
import org.cloudfoundry.identity.uaa.util.SessionUtils;
import org.cloudfoundry.identity.uaa.util.UaaSecurityContextUtils;
import org.cloudfoundry.identity.uaa.util.UaaStringUtils;
import org.springframework.security.authentication.AuthenticationDetailsSource;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.cloudfoundry.identity.uaa.oauth.token.TokenConstants.GRANT_TYPE_JWT_BEARER;
import static org.cloudfoundry.identity.uaa.oauth.token.TokenConstants.GRANT_TYPE_PASSWORD;
import static org.cloudfoundry.identity.uaa.oauth.token.TokenConstants.GRANT_TYPE_SAML2_BEARER;

/**
 * Provides an implementation that sets the UserAuthentication
 * prior to createAuthorizatioRequest is called.
 * Backwards compatible with Spring Security Oauth2 v1
 * This is a copy of the TokenEndpointAuthenticationFilter from Spring Security Oauth2 v2, but made to work with UAA
 */
@Slf4j
public class BackwardsCompatibleTokenEndpointAuthenticationFilter implements Filter {

    /**
     * A source of authentication details for requests that result in authentication.
     */
    @Setter
    private AuthenticationDetailsSource<HttpServletRequest, ?> authenticationDetailsSource = new WebAuthenticationDetailsSource();

    /**
     * An authentication entry point that can handle unsuccessful authentication.
     * Defaults to an {@link OAuth2AuthenticationEntryPoint}.
     */
    @Setter
    private AuthenticationEntryPoint authenticationEntryPoint = new OAuth2AuthenticationEntryPoint();

    private final AuthenticationManager authenticationManager;

    private final OAuth2RequestFactory oAuth2RequestFactory;

//    private final SAMLProcessingFilter samlAuthenticationFilter;

    private final ExternalOAuthAuthenticationManager externalOAuthAuthenticationManager;

    public BackwardsCompatibleTokenEndpointAuthenticationFilter(AuthenticationManager authenticationManager,
                                                                OAuth2RequestFactory oAuth2RequestFactory) {
        this(authenticationManager, oAuth2RequestFactory, null);
    }

    /**
     * @param authenticationManager an AuthenticationManager for the incoming request
     */
    public BackwardsCompatibleTokenEndpointAuthenticationFilter(AuthenticationManager authenticationManager,
                                                                OAuth2RequestFactory oAuth2RequestFactory,
//                                                                SAMLProcessingFilter samlAuthenticationFilter,
                                                                ExternalOAuthAuthenticationManager externalOAuthAuthenticationManager) {
        super();
        this.authenticationManager = authenticationManager;
        this.oAuth2RequestFactory = oAuth2RequestFactory;
//        this.samlAuthenticationFilter = samlAuthenticationFilter;
        this.externalOAuthAuthenticationManager = externalOAuthAuthenticationManager;
    }

    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        final HttpServletRequest request = (HttpServletRequest) req;
        final HttpServletResponse response = (HttpServletResponse) res;

        try {
            Authentication userAuthentication = attemptTokenAuthentication(request, response);

            if (userAuthentication != null) {
                Authentication clientAuth = SecurityContextHolder.getContext().getAuthentication();
                if (clientAuth == null) {
                    throw new BadCredentialsException(
                            "No client authentication found. Remember to put a filter upstream of the TokenEndpointAuthenticationFilter.");
                }

                Map<String, String> map = getSingleValueMap(request);
                map.put(OAuth2Utils.CLIENT_ID, clientAuth.getName());

                SecurityContextHolder.getContext().setAuthentication(userAuthentication);
                AuthorizationRequest authorizationRequest = oAuth2RequestFactory.createAuthorizationRequest(map);

                if (clientAuth.isAuthenticated()) {
                    // Ensure the OAuth2Authentication is authenticated
                    authorizationRequest.setApproved(true);
                    String clientAuthentication = UaaSecurityContextUtils.getClientAuthenticationMethod(clientAuth);
                    if (clientAuthentication != null) {
                        authorizationRequest.getExtensions().put(ClaimConstants.CLIENT_AUTH_METHOD, clientAuthentication);
                    }
                }

                OAuth2Request storedOAuth2Request = oAuth2RequestFactory.createOAuth2Request(authorizationRequest);

                SecurityContextHolder
                        .getContext()
                        .setAuthentication(new OAuth2Authentication(storedOAuth2Request, userAuthentication));

                onSuccessfulAuthentication(request, response, userAuthentication);
            }
        } catch (AuthenticationException failed) {
            log.debug("Authentication request failed: {}", failed.getMessage());
            onUnsuccessfulAuthentication(request, response, failed);
            authenticationEntryPoint.commence(request, response, failed);
            return;
        } catch (OAuth2Exception failed) {
            String message = failed.getMessage();
            log.debug("Authentication request failed with Oauth exception: {}", message);
            InsufficientAuthenticationException ex = new InsufficientAuthenticationException(message, failed);
            onUnsuccessfulAuthentication(request, response, ex);
            authenticationEntryPoint.commence(request, response, ex);
            return;
        }

        chain.doFilter(request, response);
    }

    private Map<String, String> getSingleValueMap(HttpServletRequest request) {
        Map<String, String> map = new HashMap<>();
        Map<String, String[]> parameters = request.getParameterMap();
        for (String key : parameters.keySet()) {
            String[] values = parameters.get(key);
            map.put(key, values != null && values.length > 0 ? values[0] : null);
        }
        return map;
    }

    protected void onSuccessfulAuthentication(HttpServletRequest request,
                                              HttpServletResponse response,
                                              Authentication authResult) {
    }

    protected void onUnsuccessfulAuthentication(HttpServletRequest request,
                                                HttpServletResponse response,
                                                AuthenticationException failed) {
        SecurityContextHolder.clearContext();
    }

    /**
     * If the incoming request contains user credentials in headers or parameters then extract them here into an
     * Authentication token that can be validated later. This implementation only recognises password grant requests and
     * extracts the username and password.
     *
     * @param request the incoming request, possibly with user credentials
     * @return an authentication for validation (or null if there is no further authentication)
     */
    protected Authentication extractCredentials(HttpServletRequest request) {
        String username = request.getParameter("username");
        String password = request.getParameter("password");
        UsernamePasswordAuthenticationToken credentials = new UsernamePasswordAuthenticationToken(username, password);
        credentials.setDetails(authenticationDetailsSource.buildDetails(request));
        return credentials;
    }

    protected Authentication attemptTokenAuthentication(HttpServletRequest request, HttpServletResponse response) {
        String grantType = request.getParameter("grant_type");
        log.debug("Processing token user authentication for grant:{}", UaaStringUtils.getCleanedUserControlString(grantType));
        Authentication authResult = null;
        if (GRANT_TYPE_PASSWORD.equals(grantType)) {
            Authentication credentials = extractCredentials(request);
            log.debug("Authentication credentials found password grant for '" + credentials.getName() + "'");
            authResult = authenticationManager.authenticate(credentials);

            if (authResult != null && authResult.isAuthenticated() && authResult instanceof UaaAuthentication uaaAuthentication) {
                if (SessionUtils.isPasswordChangeRequired(request.getSession())) {
                    throw new PasswordChangeRequiredException(uaaAuthentication, "password change required");
                }
            }

            return authResult;
        } else if (GRANT_TYPE_SAML2_BEARER.equals(grantType)) {
//            logger.debug(GRANT_TYPE_SAML2_BEARER +" found. Attempting authentication with assertion");
//            String assertion = request.getParameter("assertion");
//            if (assertion != null && samlAuthenticationFilter != null) {
//                logger.debug("Attempting SAML authentication for token endpoint.");
//                authResult = samlAuthenticationFilter.attemptAuthentication(request, response);
//            } else {
//                logger.debug("No assertion or filter, not attempting SAML authentication for token endpoint.");
//                throw new InsufficientAuthenticationException("SAML Assertion is missing");
//            }
        } else if (GRANT_TYPE_JWT_BEARER.equals(grantType)) {
            log.debug(GRANT_TYPE_JWT_BEARER + " found. Attempting authentication with assertion");
            String assertion = request.getParameter("assertion");
            if (assertion != null && externalOAuthAuthenticationManager != null) {
                log.debug("Attempting OIDC JWT authentication for token endpoint.");
                ExternalOAuthCodeToken token = new ExternalOAuthCodeToken(null, null, null, assertion, null, null);
                token.setRequestContextPath(getContextPath(request));
                authResult = externalOAuthAuthenticationManager.authenticate(token);
            } else {
                log.debug("No assertion or authentication manager, not attempting JWT bearer authentication for token endpoint.");
                throw new InsufficientAuthenticationException("Assertion is missing");
            }
        }
        if (authResult != null && authResult.isAuthenticated()) {
            log.debug("Authentication success: " + authResult.getName());
            return authResult;
        }
        return null;
    }

    private String getContextPath(HttpServletRequest request) {
        StringBuffer requestURL = request.getRequestURL();
        return requestURL.substring(0, requestURL.length() - request.getServletPath().length());
    }
}
