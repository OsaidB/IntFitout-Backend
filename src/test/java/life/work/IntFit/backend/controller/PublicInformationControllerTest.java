package life.work.IntFit.backend.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Public routes with the real resource converter, no application context, startup hooks or database. */
class PublicInformationControllerTest {
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new PublicInformationController()).build();
    }

    @ParameterizedTest
    @CsvSource({
            "/about,static/about.html",
            "/privacy,static/privacy.html",
            "/data-deletion,static/data-deletion.html"
    })
    void publicGetServesTheExactClasspathResourceAsUtf8Html(String route, String resource) throws Exception {
        byte[] expected;
        try (var input = new ClassPathResource(resource).getInputStream()) {
            expected = input.readAllBytes();
        }
        mvc.perform(get(route)) // Deliberately no authentication header.
                .andExpect(status().isOk())
                .andExpect(content().contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8)))
                .andExpect(content().encoding(StandardCharsets.UTF_8))
                .andExpect(content().bytes(expected));
    }

    @Test
    void aboutHasArabicBusinessDetailsAndWorkingContactAndPolicyLinks() throws Exception {
        mvc.perform(get("/about"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<html lang=\"ar\" dir=\"rtl\">")))
                .andExpect(content().string(containsString("<title>جمال البابا للطراشة والجبصين | AL-BABA Interiors</title>")))
                .andExpect(content().string(matchesPattern("(?s).*<h1>جمال البابا للطراشة والجبصين</h1>\\s*<p class=\"english-brand\" lang=\"en\" dir=\"ltr\">AL-BABA Interiors</p>.*")))
                .andExpect(content().string(containsString("خدمات التشطيبات الداخلية، بما في ذلك أعمال الجبس والدهان.")))
                .andExpect(content().string(containsString("بيتونيا، رام الله، الضفة الغربية، فلسطين.")))
                .andExpect(content().string(containsString("Osaid Jamal Baba")))
                .andExpect(content().string(containsString("href=\"tel:+970599783048\"")))
                .andExpect(content().string(containsString("href=\"mailto:office.albaba@gmail.com\"")))
                .andExpect(content().string(containsString("href=\"/privacy\"")))
                .andExpect(content().string(containsString("href=\"/data-deletion\"")));
    }

    @Test
    void aboutHasNoPostOperation() throws Exception {
        mvc.perform(post("/about")).andExpect(status().isMethodNotAllowed());
    }
}
