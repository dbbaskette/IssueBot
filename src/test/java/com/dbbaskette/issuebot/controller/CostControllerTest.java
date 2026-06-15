package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.repository.CostTrackingRepository;
import com.dbbaskette.issuebot.repository.TrackedIssueRepository;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import com.dbbaskette.issuebot.service.polling.IssuePollingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CostControllerTest {

    @Test
    void rangeCutoff_7dAnd30d_areRelativeToNow() {
        LocalDateTime now = LocalDateTime.now();
        assertThat(CostController.rangeCutoff("7d").toLocalDate())
                .isEqualTo(now.minusDays(7).toLocalDate());
        assertThat(CostController.rangeCutoff("30d").toLocalDate())
                .isEqualTo(now.minusDays(30).toLocalDate());
    }

    @Test
    void rangeCutoff_allOrNullOrUnknown_isEpoch() {
        LocalDate epoch = LocalDate.of(1970, 1, 1);
        assertThat(CostController.rangeCutoff("all").toLocalDate()).isEqualTo(epoch);
        assertThat(CostController.rangeCutoff(null).toLocalDate()).isEqualTo(epoch);
        assertThat(CostController.rangeCutoff("bogus").toLocalDate()).isEqualTo(epoch);
    }

    @Test
    void buildCostSeries_mapsDayRowsWithRoundedCost() {
        List<Object[]> rows = List.of(
                new Object[]{LocalDate.of(2026, 6, 1), new BigDecimal("1.23")},
                new Object[]{java.sql.Date.valueOf("2026-06-02"), new BigDecimal("2.5")}
        );

        List<Map<String, Object>> series = CostController.buildCostSeries(rows);

        assertThat(series).hasSize(2);
        assertThat(series.get(0).get("label")).isEqualTo("2026-06-01");
        assertThat(series.get(0).get("cost")).isEqualTo(new BigDecimal("1.2300"));
        assertThat(series.get(1).get("label")).isEqualTo("2026-06-02");
        assertThat(series.get(1).get("cost")).isEqualTo(new BigDecimal("2.5000"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void costs_addsCostSeriesAndSelectedRangeToModel() {
        CostTrackingRepository costRepo = mock(CostTrackingRepository.class);
        TrackedIssueRepository issueRepo = mock(TrackedIssueRepository.class);
        WatchedRepoRepository repoRepo = mock(WatchedRepoRepository.class);
        IssuePollingService polling = mock(IssuePollingService.class);

        when(costRepo.totalCost()).thenReturn(BigDecimal.ZERO);
        when(repoRepo.findAll()).thenReturn(List.of());
        when(issueRepo.findByStatusIn(any())).thenReturn(List.of());
        when(costRepo.sumCostByDay(any(LocalDateTime.class)))
                .thenReturn(List.<Object[]>of(new Object[]{LocalDate.of(2026, 6, 1), new BigDecimal("3.5")}));

        CostController controller = new CostController(costRepo, issueRepo, repoRepo, polling, new ObjectMapper());

        Model model = new ExtendedModelMap();
        controller.costs(model, null, "7d");

        assertThat(model.getAttribute("selectedRange")).isEqualTo("7d");
        List<Map<String, Object>> series = (List<Map<String, Object>>) model.getAttribute("costSeries");
        assertThat(series).isNotNull().hasSize(1);
        assertThat(series.get(0).get("label")).isEqualTo("2026-06-01");
        assertThat(model.getAttribute("costSeriesJson")).isNotNull();
        // 7d range must query with a cutoff ~7 days ago, not epoch.
        verify(costRepo).sumCostByDay(argThat(since ->
                since.isAfter(LocalDateTime.now().minusDays(8))
                        && since.isBefore(LocalDateTime.now().minusDays(6))));
    }
}
