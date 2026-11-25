package com.settlementmatcher.match;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.settlementmatcher.config.MatcherProperties;
import com.settlementmatcher.domain.BankTxn;
import com.settlementmatcher.domain.LedgerEntry;
import com.settlementmatcher.domain.MatchResult;
import com.settlementmatcher.domain.MatchResult.Flagged;
import com.settlementmatcher.domain.MatchResult.MatchedPair;

/**
 * 은행 입금과 장부 매출을 대사한다.
 *
 * <ol>
 * <li>출금은 매칭에서 뺀다(원본 시트에만 남음).</li>
 * <li>적요에 비매출 키워드가 있는 입금은 '검토 필요'로 보낸다.</li>
 * <li>날짜·금액·거래처명 세 조건을 모두 통과한 (은행, 장부) 쌍을 후보로 잇는다.</li>
 * <li>후보로 이어진 묶음이 은행 1건 + 장부 1건이면 '일치', 3건 이상이면 묶음 전체를 '검토 필요'로 보낸다.</li>
 * <li>후보가 하나도 없는 건은 '불일치'이며, 조건 하나만 어긋난 상대가 있으면 그 이유를 사유로 적는다.</li>
 * </ol>
 */
@Service
public class SettlementMatcher {

    private final MatcherProperties props;
    private final NameNormalizer normalizer;

    public SettlementMatcher(MatcherProperties props) {
        this.props = props;
        this.normalizer = new NameNormalizer(props.nameStripTokens());
    }

    public MatchResult match(List<BankTxn> bankAll, List<LedgerEntry> ledger) {
        List<BankTxn> deposits = bankAll.stream().filter(b -> b.deposit() > 0).toList();
        int excludedWithdrawals = bankAll.size() - deposits.size();

        List<Flagged> review = new ArrayList<>();
        List<BankTxn> targets = new ArrayList<>();
        int group = 0;
        for (BankTxn b : deposits) {
            Optional<String> keyword = nonSalesKeyword(b);
            if (keyword.isPresent()) {
                review.add(new Flagged(++group, b, null, "비매출 입금 추정 (적요 키워드 '" + keyword.get() + "')"));
            } else {
                targets.add(b);
            }
        }

        int nb = targets.size();
        int nl = ledger.size();
        String[] bankNames = targets.stream().map(b -> normalizer.normalize(b.name())).toArray(String[]::new);
        String[] ledgerNames = ledger.stream().map(l -> normalizer.normalize(l.name())).toArray(String[]::new);

        // 노드 0..nb-1 = 은행, nb..nb+nl-1 = 장부
        UnionFind uf = new UnionFind(nb + nl);
        int[] degree = new int[nb + nl];
        for (int i = 0; i < nb; i++) {
            for (int j = 0; j < nl; j++) {
                if (evaluate(targets.get(i), bankNames[i], ledger.get(j), ledgerNames[j]).ok()) {
                    uf.union(i, nb + j);
                    degree[i]++;
                    degree[nb + j]++;
                }
            }
        }

        Map<Integer, List<Integer>> components = new LinkedHashMap<>();
        for (int node = 0; node < nb + nl; node++) {
            if (degree[node] > 0) {
                components.computeIfAbsent(uf.find(node), k -> new ArrayList<>()).add(node);
            }
        }

        List<MatchedPair> matched = new ArrayList<>();
        for (List<Integer> comp : components.values()) {
            if (comp.size() == 2) {
                int i = comp.get(0) < nb ? comp.get(0) : comp.get(1);
                int j = (comp.get(0) < nb ? comp.get(1) : comp.get(0)) - nb;
                matched.add(toPair(targets.get(i), bankNames[i], ledger.get(j), ledgerNames[j]));
            } else {
                group++;
                long banks = comp.stream().filter(n -> n < nb).count();
                String reason = "후보 중복: 은행 " + banks + "건 · 장부 " + (comp.size() - banks) + "건이 서로 겹침";
                for (int node : comp) {
                    review.add(node < nb
                            ? new Flagged(group, targets.get(node), null, reason)
                            : new Flagged(group, null, ledger.get(node - nb), reason));
                }
            }
        }

        List<Integer> loneBanks = new ArrayList<>();
        List<Integer> loneLedgers = new ArrayList<>();
        for (int i = 0; i < nb; i++) {
            if (degree[i] == 0) loneBanks.add(i);
        }
        for (int j = 0; j < nl; j++) {
            if (degree[nb + j] == 0) loneLedgers.add(j);
        }

        List<Flagged> unmatched = new ArrayList<>();
        for (int j : loneLedgers) {
            LedgerEntry l = ledger.get(j);
            Near near = nearest(loneBanks, i -> evaluate(targets.get(i), bankNames[i], l, ledgerNames[j]));
            String reason = near == null
                    ? "은행에 대응 입금 없음"
                    : near.eval.failReason() + " · 후보: 은행 " + targets.get(near.index).rowNo() + "행 "
                            + targets.get(near.index).name() + " " + won(targets.get(near.index).deposit());
            unmatched.add(new Flagged(0, null, l, reason));
        }
        for (int i : loneBanks) {
            BankTxn b = targets.get(i);
            Near near = nearest(loneLedgers, j -> evaluate(b, bankNames[i], ledger.get(j), ledgerNames[j]));
            String reason = near == null
                    ? "장부에 대응 거래 없음"
                    : near.eval.failReason() + " · 후보: 장부 " + ledger.get(near.index).id() + " "
                            + ledger.get(near.index).name() + " " + won(ledger.get(near.index).amount());
            unmatched.add(new Flagged(0, b, null, reason));
        }

        matched.sort(Comparator.comparing((MatchedPair p) -> p.ledger().date())
                .thenComparingInt(p -> p.ledger().rowNo()));
        unmatched.sort(Comparator.comparing((Flagged f) -> f.isBank() ? f.bank().date() : f.ledger().date())
                .thenComparing(Flagged::isBank));
        review.sort(Comparator.comparingInt(Flagged::group).thenComparing(Flagged::isBank));
        return new MatchResult(List.copyOf(matched), List.copyOf(unmatched), List.copyOf(review), excludedWithdrawals);
    }

    private Optional<String> nonSalesKeyword(BankTxn b) {
        String memo = b.memo() == null ? "" : b.memo();
        return props.nonSalesMemoKeywords().stream().filter(k -> !k.isBlank() && memo.contains(k)).findFirst();
    }

    /** 조건 하나만 어긋난 상대 중 날짜·금액 차이가 가장 작은 것. 없으면 null. */
    private static Near nearest(List<Integer> others, java.util.function.IntFunction<Eval> eval) {
        Near best = null;
        for (int idx : others) {
            Eval e = eval.apply(idx);
            if (e.failCount() != 1) continue;
            if (best == null || e.distance() < best.eval.distance()) {
                best = new Near(idx, e);
            }
        }
        return best;
    }

    private record Near(int index, Eval eval) {
    }

    Eval evaluate(BankTxn b, String bankName, LedgerEntry l, String ledgerName) {
        long dayDiff = ChronoUnit.DAYS.between(l.date(), b.date());
        boolean dateOk = Math.abs(dayDiff) <= props.dateToleranceDays();

        long amountDiff = l.amount() - b.deposit(); // 양수 = 입금이 적음(수수료 차감 가능)
        BigDecimal allowed = BigDecimal.valueOf(l.amount()).multiply(props.feeToleranceRate());
        boolean amountOk = amountDiff >= 0 && BigDecimal.valueOf(amountDiff).compareTo(allowed) <= 0;

        NameMatch nameMatch = compareNames(bankName, ledgerName);
        return new Eval(dateOk, amountOk, nameMatch, dayDiff, amountDiff, b, l);
    }

    private NameMatch compareNames(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return NameMatch.NONE;
        if (a.equals(b)) return NameMatch.EXACT;
        String shorter = a.length() <= b.length() ? a : b;
        String longer = shorter == a ? b : a;
        if (!longer.contains(shorter)) return NameMatch.NONE;
        return shorter.length() >= props.minPartialNameLength() ? NameMatch.PARTIAL : NameMatch.TOO_SHORT;
    }

    private enum NameMatch {
        EXACT, PARTIAL, TOO_SHORT, NONE;

        boolean ok() {
            return this == EXACT || this == PARTIAL;
        }
    }

    final class Eval {
        final boolean dateOk;
        final boolean amountOk;
        final NameMatch name;
        final long dayDiff;
        final long amountDiff;
        final BankTxn bank;
        final LedgerEntry ledger;

        Eval(boolean dateOk, boolean amountOk, NameMatch name, long dayDiff, long amountDiff, BankTxn bank,
                LedgerEntry ledger) {
            this.dateOk = dateOk;
            this.amountOk = amountOk;
            this.name = name;
            this.dayDiff = dayDiff;
            this.amountDiff = amountDiff;
            this.bank = bank;
            this.ledger = ledger;
        }

        boolean ok() {
            return dateOk && amountOk && name.ok();
        }

        int failCount() {
            return (dateOk ? 0 : 1) + (amountOk ? 0 : 1) + (name.ok() ? 0 : 1);
        }

        long distance() {
            return Math.abs(dayDiff) * 1_000_000_000L + Math.abs(amountDiff);
        }

        String failReason() {
            if (!dateOk) {
                return (dayDiff > 0 ? "입금이 장부보다 " + dayDiff + "일 늦음" : "입금이 장부보다 " + (-dayDiff) + "일 빠름")
                        + " (허용 ±" + props.dateToleranceDays() + "일)";
            }
            if (!amountOk) {
                if (amountDiff < 0) {
                    return "과입금 " + won(-amountDiff) + " (입금액이 장부보다 많음)";
                }
                return "금액 차이 " + won(amountDiff) + " (" + percent(amountDiff, ledger.amount()) + ", 허용 "
                        + props.feeToleranceRate().movePointRight(2).stripTrailingZeros().toPlainString() + "%)";
            }
            if (name == NameMatch.TOO_SHORT) {
                return "거래처명이 짧아 부분 일치 제외 (최소 " + props.minPartialNameLength() + "자, 은행 '" + bank.name()
                        + "' / 장부 '" + ledger.name() + "')";
            }
            return "거래처명 불일치 (은행 '" + bank.name() + "' / 장부 '" + ledger.name() + "')";
        }
    }

    private MatchedPair toPair(BankTxn b, String bankName, LedgerEntry l, String ledgerName) {
        Eval e = evaluate(b, bankName, l, ledgerName);
        List<String> notes = new ArrayList<>();
        if (e.dayDiff != 0) {
            notes.add("입금 " + (e.dayDiff > 0 ? "+" : "") + e.dayDiff + "일");
        }
        if (e.amountDiff > 0) {
            notes.add("수수료 차감 추정 " + won(e.amountDiff));
        }
        if (e.name == NameMatch.PARTIAL) {
            notes.add("거래처명 부분 일치");
        } else if (!b.name().strip().equals(l.name().strip())) {
            notes.add("거래처명 정규화 후 일치");
        }
        return new MatchedPair(b, l, e.dayDiff, e.amountDiff, String.join(" · ", notes));
    }

    private static String won(long amount) {
        return String.format(Locale.ROOT, "%,d원", amount);
    }

    private static String percent(long part, long whole) {
        if (whole == 0) return "-";
        return String.format(Locale.ROOT, "%.2f%%", part * 100.0 / whole);
    }

    private static final class UnionFind {
        private final int[] parent;

        UnionFind(int n) {
            parent = new int[n];
            for (int i = 0; i < n; i++) parent[i] = i;
        }

        int find(int x) {
            while (parent[x] != x) {
                parent[x] = parent[parent[x]];
                x = parent[x];
            }
            return x;
        }

        void union(int a, int b) {
            parent[find(a)] = find(b);
        }
    }
}
