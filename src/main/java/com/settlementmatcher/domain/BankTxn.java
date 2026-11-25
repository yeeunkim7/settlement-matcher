package com.settlementmatcher.domain;

import java.time.LocalDate;

/** 은행 거래내역 한 행. rowNo는 원본 파일의 행 번호(1부터). */
public record BankTxn(int rowNo, LocalDate date, String memo, String name, long withdrawal, long deposit) {
}
