package vasconcelos.silvio.crochet_back.pattern;

import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

final class PatternJson {

    static final String CHAT = """
            {
              "title": "Amigurumi Chat",
              "description": "Petit chat",
              "hookSizeMm": 3.5,
              "yarn": "Coton 8/4",
              "sourceUrl": "https://example.com/chat",
              "imageUrl": "https://example.com/chat.jpg",
              "steps": [
                { "title": "Tête", "rows": [
                    { "number": "1", "value": "6ms dans cercle magique (6)" },
                    { "number": "2", "value": "6aug (12)" },
                    { "number": "3", "value": "3ms, [1ms, 4aug]x6 (34)" } ] },
                { "title": "Corps", "rows": [
                    { "number": "1-10", "value": "34ms (34)" } ] }
              ]
            }
            """;

    static final String VALID_STEPS = """
            [ { "title": "A", "rows": [ { "number": "1", "value": "6ms" } ] } ]""";

    private PatternJson() {
    }

    /** {@code fields} is a JSON fragment ending with a comma, e.g. {@code "\"title\": \"T\","}. */
    static String withSteps(String fields, String steps) {
        return "{" + fields + "\"steps\": " + steps + "}";
    }

    static String titled(String title) {
        return withSteps("\"title\": \"" + title + "\",", VALID_STEPS);
    }

    /** POSTs the pattern, asserts 201 and returns the Location header. */
    static String create(MockMvc mvc, String json) throws Exception {
        return mvc.perform(post("/api/patterns").contentType(APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
    }
}
