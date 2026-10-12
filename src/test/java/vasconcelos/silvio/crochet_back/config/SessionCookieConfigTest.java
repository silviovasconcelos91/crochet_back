package vasconcelos.silvio.crochet_back.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.Cookie;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.web.http.CookieSerializer.CookieValue;
import org.springframework.session.web.http.DefaultCookieSerializer;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SessionCookieConfigTest {

    @Test
    void appliesEveryServerSessionCookieProperty() {
        ServerProperties properties = new ServerProperties();
        Cookie cookie = properties.getServlet().getSession().getCookie();
        cookie.setName("CROCHET_SESSION");
        cookie.setDomain("crochet.example");
        cookie.setPath("/api");
        cookie.setMaxAge(Duration.ofDays(7));
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setSameSite(Cookie.SameSite.STRICT);
        cookie.setPartitioned(true);

        String header = write(new SessionCookieConfig().cookieSerializer(properties));

        assertThat(header)
                .startsWith("CROCHET_SESSION=")
                .contains("Domain=crochet.example", "Path=/api", "Max-Age=604800", "HttpOnly", "Secure",
                        "SameSite=Strict", "Partitioned");
    }

    @Test
    void keepsSpringSessionDefaultsForUnsetProperties() {
        String header = write(new SessionCookieConfig().cookieSerializer(new ServerProperties()));

        assertThat(header).startsWith("SESSION=").contains("Path=/").doesNotContain("Max-Age", "Domain");
    }

    private static String write(DefaultCookieSerializer serializer) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        serializer.writeCookieValue(new CookieValue(request, response, "value"));
        return response.getHeader("Set-Cookie");
    }
}
