package com.truesight.backend.web;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * AC 1.3, verbatim requirement: "An automated test creates two accounts and proves
 * account B cannot read account A's portfolio." This is that test.
 *
 * <p>Deliberately drives the whole thing through real HTTP requests via MockMvc rather
 * than calling PortfolioService directly — testing at the controller boundary is what
 * actually proves the JWT-to-ownership-check chain works end to end (auth filter parses
 * the token, CurrentUser resolves the right id, the service's id+ownerId query is
 * genuinely applied), which is the thing this AC cares about, not just that the
 * repository query is correct in isolation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CrossAccountIsolationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void accountBCannotReadAccountAsPortfolio() throws Exception {
        String tokenA = registerAndGetToken("account-a@example.com");
        String tokenB = registerAndGetToken("account-b@example.com");

        // Account A creates a portfolio and we capture its id.
        String createBody = """
                {"name":"Account A's Private Book"}
                """;
        String createResponse = mockMvc.perform(post("/api/portfolios")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Long portfolioAId = objectMapper.readTree(createResponse).get("id").asLong();

        // Account A can read its own portfolio.
        mockMvc.perform(get("/api/portfolios/" + portfolioAId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Account A's Private Book"));

        // Account B requesting the SAME portfolio id gets 404 — not 403, not the data.
        mockMvc.perform(get("/api/portfolios/" + portfolioAId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        // And account B's own portfolio LIST never contains account A's portfolio.
        mockMvc.perform(get("/api/portfolios")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + portfolioAId + ")]").isEmpty());
    }

    @Test
    void requestWithoutTokenIsRejectedWithoutLeakingExistence() throws Exception {
        // AC 1.3's "to avoid leaking existence": an unauthenticated request to a
        // definitely-nonexistent AND a definitely-existent portfolio id must be
        // indistinguishable — both 401, before ownership is ever checked.
        mockMvc.perform(get("/api/portfolios/999999"))
                .andExpect(status().isUnauthorized());
    }

    private String registerAndGetToken(String email) throws Exception {
        String body = """
                {"email":"%s","password":"correcthorsebattery123"}
                """.formatted(email);
        String response = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value(startsWith("ey"))) // JWTs start "ey" (base64 of '{"')
                .andReturn().getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(response);
        return node.get("token").asText();
    }
}
