package com.settlementmatcher.domain;

import java.util.List;

public record MatchResult(
        List<MatchedPair> matched,
        List<Flagged> unmatched,
        List<Flagged> review,
        int excludedWithdrawals) {

    /** 일치: 은행 1건과 장부 1건이 서로를 유일한 후보로 가진 경우. */
    public record MatchedPair(BankTxn bank, LedgerEntry ledger, long dayDiff, long amountDiff, String note) {
    }

    /**
     * 불일치 또는 검토 필요 항목. bank와 ledger 중 정확히 하나만 값이 있다.
     * group은 검토 필요에서 함께 봐야 하는 건끼리 같은 번호를 가진다(불일치는 0).
     */
    public record Flagged(int group, BankTxn bank, LedgerEntry ledger, String reason) {
        public boolean isBank() {
            return bank != null;
        }
    }
}
