package de.kopis.withings.api;

import org.scribe.builder.api.DefaultApi10a;
import org.scribe.model.Token;

public class WithingsApi extends DefaultApi10a {

    public static final String ACCESS_TOKEN_URL = "https://oauth.withings.com/account/authorize";

    @Override
    public String getRequestTokenEndpoint() {
        return "https://oauth.withings.com/account/request_token";
    }

    @Override
    public String getAccessTokenEndpoint() {
        return "https://oauth.withings.com/account/access_token";
    }

    @Override
    public String getAuthorizationUrl(Token requestToken) {
        return String.format(ACCESS_TOKEN_URL + "?oauth_token=%s", requestToken.getToken());
    }

}
