package com.settlementmatcher.domain;

import java.time.LocalDate;

/** 매출 장부 한 행. rowNo는 원본 파일의 행 번호(1부터). */
public record LedgerEntry(int rowNo, String id, LocalDate date, String name, long amount) {
}
