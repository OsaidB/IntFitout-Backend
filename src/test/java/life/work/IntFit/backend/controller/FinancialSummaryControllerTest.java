package life.work.IntFit.backend.controller;

import life.work.IntFit.backend.dto.CurrentTotalOwedDTO;
import life.work.IntFit.backend.service.FinancialSummaryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract test for GET /api/financial-summary/current-total-owed.
 * The service is mocked; only the HTTP/JSON contract is asserted.
 */
@WebMvcTest(FinancialSummaryController.class)
class FinancialSummaryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FinancialSummaryService financialSummaryService;

    @Test
    void returnsExactJsonContract() throws Exception {
        CurrentTotalOwedDTO dto = CurrentTotalOwedDTO.builder()
                .debt(new BigDecimal("370246.00"))
                .checksNotDueYet(new BigDecimal("70000.00"))
                .totalIncludingChecks(new BigDecimal("440246.00"))
                .calculatedAt(OffsetDateTime.of(2026, 7, 17, 13, 0, 0, 0, ZoneOffset.ofHours(3)))
                .build();

        when(financialSummaryService.getCurrentTotalOwed()).thenReturn(dto);

        String expectedJson = """
                {
                  "debt": 370246.00,
                  "checksNotDueYet": 70000.00,
                  "totalIncludingChecks": 440246.00,
                  "calculatedAt": "2026-07-17T13:00:00+03:00"
                }
                """;

        mockMvc.perform(get("/api/financial-summary/current-total-owed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calculatedAt").value("2026-07-17T13:00:00+03:00"))
                .andExpect(content().json(expectedJson, true)); // strict: exactly these fields

        verify(financialSummaryService, times(1)).getCurrentTotalOwed();
    }
}
