package vasconcelos.silvio.crochet_back.support;

import jakarta.servlet.http.Cookie;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the real session + CSRF flow: keeps cookies between requests like a browser and echoes the
 * {@code XSRF-TOKEN} cookie in the {@code X-XSRF-TOKEN} header like the SPA front will.
 */
public final class Browser {

    private final MockMvc mvc;
    private final Map<String, Cookie> cookies = new LinkedHashMap<>();

    public Browser(MockMvc mvc) {
        this.mvc = mvc;
    }

    public ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        if (!cookies.isEmpty()) {
            request.cookie(cookies.values().toArray(Cookie[]::new));
        }
        Cookie xsrf = cookies.get("XSRF-TOKEN");
        if (xsrf != null) {
            request.header("X-XSRF-TOKEN", xsrf.getValue());
        }
        ResultActions result = mvc.perform(request);
        for (Cookie cookie : result.andReturn().getResponse().getCookies()) {
            if (cookie.getMaxAge() == 0) {
                cookies.remove(cookie.getName());
            } else {
                cookies.put(cookie.getName(), cookie);
            }
        }
        return result;
    }

    /** Fetches a CSRF token, as the front does before its first POST. */
    public Browser fetchCsrf() throws Exception {
        perform(get("/api/auth/csrf")).andExpect(status().isNoContent());
        return this;
    }

    public Cookie cookie(String name) {
        return cookies.get(name);
    }
}
