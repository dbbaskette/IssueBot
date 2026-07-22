package com.dbbaskette.issuebot.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetProgressTest {

    @Test
    void nullBudgetHasNoProgress() {
        assertThat(BudgetProgress.percent(new BigDecimal("2.50"), null)).isZero();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0.00", "-0.01"})
    void nullZeroOrNegativeSpendHasNoProgress(String spent) {
        BigDecimal amount = spent == null ? null : new BigDecimal(spent);

        assertThat(BudgetProgress.percent(amount, new BigDecimal("10.00"))).isZero();
    }

    @Test
    void zeroBudgetWithPositiveSpendIsFullyExhausted() {
        assertThat(BudgetProgress.percent(new BigDecimal("0.01"), BigDecimal.ZERO)).isEqualTo(100);
    }

    @Test
    void roundsDownBelowWarningThreshold() {
        assertThat(BudgetProgress.percent(new BigDecimal("7.99"), new BigDecimal("10.00")))
                .isEqualTo(79);
    }

    @Test
    void clampsSpendAboveBudgetToOneHundred() {
        assertThat(BudgetProgress.percent(new BigDecimal("12.50"), new BigDecimal("10.00")))
                .isEqualTo(100);
    }
}
