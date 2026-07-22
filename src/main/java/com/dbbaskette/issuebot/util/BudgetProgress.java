package com.dbbaskette.issuebot.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class BudgetProgress {
    private BudgetProgress() {
    }

    public static int percent(BigDecimal spent, BigDecimal budget) {
        if (budget == null) return 0;
        if (spent == null || spent.signum() <= 0) return 0;
        if (budget.signum() <= 0) return 100;
        BigDecimal pct = spent.multiply(BigDecimal.valueOf(100))
                .divide(budget, 0, RoundingMode.DOWN);
        return pct.compareTo(BigDecimal.valueOf(100)) >= 0 ? 100 : pct.intValue();
    }
}
