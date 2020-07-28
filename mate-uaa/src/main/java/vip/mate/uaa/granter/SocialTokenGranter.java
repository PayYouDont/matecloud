package vip.mate.uaa.granter;

import com.alibaba.fastjson.JSON;
import com.xkcoding.justauth.AuthRequestFactory;
import lombok.extern.slf4j.Slf4j;
import me.zhyd.oauth.enums.AuthResponseStatus;
import me.zhyd.oauth.model.AuthCallback;
import me.zhyd.oauth.model.AuthResponse;
import me.zhyd.oauth.model.AuthUser;
import me.zhyd.oauth.request.AuthRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AccountStatusException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.common.exceptions.InvalidGrantException;
import org.springframework.security.oauth2.provider.*;
import org.springframework.security.oauth2.provider.token.AbstractTokenGranter;
import org.springframework.security.oauth2.provider.token.AuthorizationServerTokenServices;
import vip.mate.core.security.social.SocialAuthenticationToken;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 社交登录TokenGranter
 * @author pangu
 * @since 2020-7-26
 */
@Slf4j
public class SocialTokenGranter extends AbstractTokenGranter {
    private static final String GRANT_TYPE = "social";

    private final AuthenticationManager authenticationManager;

    private StringRedisTemplate stringRedisTemplate;

    private AuthRequestFactory factory;

    public SocialTokenGranter(AuthenticationManager authenticationManager,
                               AuthorizationServerTokenServices tokenServices, ClientDetailsService clientDetailsService,
                               OAuth2RequestFactory requestFactory, StringRedisTemplate stringRedisTemplate, AuthRequestFactory factory) {
        this(authenticationManager, tokenServices, clientDetailsService, requestFactory, GRANT_TYPE);
        this.stringRedisTemplate = stringRedisTemplate;
        this.factory = factory;
    }

    protected SocialTokenGranter(AuthenticationManager authenticationManager, AuthorizationServerTokenServices tokenServices,
                                  ClientDetailsService clientDetailsService, OAuth2RequestFactory requestFactory, String grantType) {
        super(tokenServices, clientDetailsService, requestFactory, grantType);
        this.authenticationManager = authenticationManager;
    }

    @Override
    protected OAuth2Authentication getOAuth2Authentication(ClientDetails client, TokenRequest tokenRequest) {
        Map<String, String> parameters = new LinkedHashMap<>(tokenRequest.getRequestParameters());
        String code = parameters.get("code");
        String state = parameters.get("state");

        String oauthType = code.split("-")[0];
        code = code.split("-")[1];

        AuthRequest authRequest = factory.get(oauthType);
        AuthCallback authCallback = AuthCallback.builder().code(code).state(state).build();
        AuthResponse response = authRequest.login(authCallback);
        log.info("【response】= {}", JSON.toJSON(response));
        AuthUser authUser = null;
        // 第三方登录成功
        if (response.getCode() == AuthResponseStatus.SUCCESS.getCode()) {
            authUser = (AuthUser) response.getData();
        }
        log.error("authUser:{}", JSON.toJSON(authUser));

        Authentication userAuth = new SocialAuthenticationToken(authUser);
        ((AbstractAuthenticationToken) userAuth).setDetails(parameters);
        try {
            userAuth = authenticationManager.authenticate(userAuth);
        }
        catch (AccountStatusException | BadCredentialsException ase) {
            //covers expired, locked, disabled cases (mentioned in section 5.2, draft 31)
            throw new InvalidGrantException(ase.getMessage());
        }
        // If the username/password are wrong the spec says we should send 400/invalid grant

        if (userAuth == null || !userAuth.isAuthenticated()) {
            throw new InvalidGrantException("Could not authenticate user: " + authUser);
        }

        OAuth2Request storedOAuth2Request = getRequestFactory().createOAuth2Request(client, tokenRequest);
        return new OAuth2Authentication(storedOAuth2Request, userAuth);
    }
}