package com.logicielpourtous.site.features.accounting;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Construit le Bilan et le Compte de Résultat à partir des soldes de balance.
 * Logique PCG français — classes 1-7.
 */
@Service
public class AccountingFinancialStatementService {

    // ── Types de données des états financiers ────────────────────────────────

    public record ReportLine(String number, String label, BigDecimal amount) {}

    public record ReportSection(String title, List<ReportLine> lines, BigDecimal total) {
        static ReportSection of(String title, List<ReportLine> lines) {
            BigDecimal t = lines.stream().map(ReportLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            return new ReportSection(title, lines, t);
        }
    }

    public record Bilan(List<ReportSection> actif, BigDecimal totalActif,
                        List<ReportSection> passif, BigDecimal totalPassif) {}

    public record CompteResultat(
        List<ReportSection> produits,   BigDecimal totalProduits,
        List<ReportSection> charges,    BigDecimal totalCharges,
        BigDecimal resultatExploitation,
        BigDecimal resultatFinancier,
        BigDecimal resultatExceptionnel,
        BigDecimal resultatNet) {}

    // ── Bilan ────────────────────────────────────────────────────────────────

    public Bilan buildBilan(List<AccountingService.BalanceLine> lines) {

        // ─ ACTIF ─────────────────────────────────────────────────────────────

        // Immobilisations incorporelles (20x)
        List<ReportLine> immoInc  = collect(lines, n -> sw(n,"20"), bl -> bl.solde());
        // Immobilisations corporelles (21x-23x)
        List<ReportLine> immoCorp = collect(lines, n -> sw(n,"21")||sw(n,"22")||sw(n,"23"), bl -> bl.solde());
        // Immobilisations financières (25x-27x)
        List<ReportLine> immoFin  = collect(lines, n -> sw(n,"25")||sw(n,"26")||sw(n,"27"), bl -> bl.solde());
        // Amortissements/dépréciations (28x, 29x) — solde créditeur négatif, réduit l'actif
        List<ReportLine> amort    = collect(lines, n -> sw(n,"28")||sw(n,"29"), bl -> bl.solde()); // montants négatifs

        List<ReportLine> immoLines = new ArrayList<>();
        immoLines.addAll(immoInc);
        immoLines.addAll(immoCorp);
        immoLines.addAll(immoFin);
        immoLines.addAll(amort);

        // Stocks (3xx)
        List<ReportLine> stocks = collect(lines, n -> sw(n,"3"), bl -> bl.solde());

        // Créances clients (41x)
        List<ReportLine> clients = collect(lines, n -> sw(n,"41"), bl -> bl.solde());

        // Autres créances : 44x/45x/46x/47x avec solde débiteur
        List<ReportLine> autresCreances = collectPosSolde(lines,
                n -> sw(n,"44")||sw(n,"45")||sw(n,"46")||sw(n,"47"));

        List<ReportLine> creanceLines = new ArrayList<>();
        creanceLines.addAll(clients);
        creanceLines.addAll(autresCreances);

        // Trésorerie active : 5xx avec solde débiteur
        List<ReportLine> treso = collectPosSolde(lines, n -> sw(n,"5"));

        List<ReportSection> actifSections = new ArrayList<>();
        addIfNotEmpty(actifSections, ReportSection.of("Actif immobilisé", immoLines));
        addIfNotEmpty(actifSections, ReportSection.of("Stocks", stocks));
        addIfNotEmpty(actifSections, ReportSection.of("Créances", creanceLines));
        addIfNotEmpty(actifSections, ReportSection.of("Trésorerie", treso));

        BigDecimal totalActif = sum(actifSections);

        // ─ PASSIF ────────────────────────────────────────────────────────────

        // Capitaux propres (10x, 11x, 12x) — solde créditeur → -solde
        List<ReportLine> caps = collect(lines,
                n -> sw(n,"10")||sw(n,"11")||sw(n,"12"), bl -> bl.solde().negate());

        // Emprunts et dettes financières (16x)
        List<ReportLine> emprunts = collect(lines, n -> sw(n,"16"), bl -> bl.solde().negate());

        // Dettes fournisseurs (40x, 408x)
        List<ReportLine> dfournisseurs = collect(lines, n -> sw(n,"40"), bl -> bl.solde().negate());

        // Dettes personnel (42x) et organismes sociaux (43x)
        List<ReportLine> dSociales = collect(lines, n -> sw(n,"42")||sw(n,"43"), bl -> bl.solde().negate());

        // Dettes fiscales (44x/45x/46x/47x avec solde créditeur)
        List<ReportLine> dFiscales = collectNegSolde(lines,
                n -> sw(n,"44")||sw(n,"45")||sw(n,"46")||sw(n,"47"));

        List<ReportLine> detteLines = new ArrayList<>();
        detteLines.addAll(emprunts);
        detteLines.addAll(dfournisseurs);
        detteLines.addAll(dSociales);
        detteLines.addAll(dFiscales);

        // Concours bancaires (5xx avec solde créditeur)
        List<ReportLine> concours = collectNegSolde(lines, n -> sw(n,"5"));

        List<ReportSection> passifSections = new ArrayList<>();
        addIfNotEmpty(passifSections, ReportSection.of("Capitaux propres", caps));
        addIfNotEmpty(passifSections, ReportSection.of("Dettes", detteLines));
        addIfNotEmpty(passifSections, ReportSection.of("Concours bancaires", concours));

        BigDecimal totalPassif = sum(passifSections);

        return new Bilan(actifSections, totalActif, passifSections, totalPassif);
    }

    // ── Compte de Résultat ───────────────────────────────────────────────────

    public CompteResultat buildCompteResultat(List<AccountingService.BalanceLine> lines) {

        // ─ PRODUITS ──────────────────────────────────────────────────────────
        // Solde des comptes de produits est créditeur → montant = -solde

        List<ReportLine> ca          = collect(lines, n -> sw(n,"70"), bl -> bl.solde().negate());
        List<ReportLine> autresProdE = collect(lines, n -> sw(n,"71")||sw(n,"72")||sw(n,"74")||sw(n,"75"), bl -> bl.solde().negate());
        List<ReportLine> prodFin     = collect(lines, n -> sw(n,"76"), bl -> bl.solde().negate());
        List<ReportLine> prodExc     = collect(lines, n -> sw(n,"77"), bl -> bl.solde().negate());

        List<ReportSection> produits = new ArrayList<>();
        addIfNotEmpty(produits, ReportSection.of("Chiffre d'affaires (70x)", ca));
        addIfNotEmpty(produits, ReportSection.of("Autres produits d'exploitation (71-75)", autresProdE));
        addIfNotEmpty(produits, ReportSection.of("Produits financiers (76x)", prodFin));
        addIfNotEmpty(produits, ReportSection.of("Produits exceptionnels (77x)", prodExc));

        BigDecimal totalProduits     = sum(produits);
        BigDecimal totalProdExploit  = sumSections(produits, "70x","71-75");
        BigDecimal totalProdFin      = sectionTotal(produits, "Produits financiers (76x)");
        BigDecimal totalProdExc      = sectionTotal(produits, "Produits exceptionnels (77x)");

        // ─ CHARGES ───────────────────────────────────────────────────────────
        // Solde des comptes de charges est débiteur → montant = solde

        List<ReportLine> achats      = collect(lines, n -> sw(n,"60")||sw(n,"61"), bl -> bl.solde());
        List<ReportLine> services    = collect(lines, n -> sw(n,"62"), bl -> bl.solde());
        List<ReportLine> impots      = collect(lines, n -> sw(n,"63"), bl -> bl.solde());
        List<ReportLine> personnel   = collect(lines, n -> sw(n,"64"), bl -> bl.solde());
        List<ReportLine> autresCharE = collect(lines, n -> sw(n,"65"), bl -> bl.solde());
        List<ReportLine> chargesFin  = collect(lines, n -> sw(n,"66"), bl -> bl.solde());
        List<ReportLine> chargesExc  = collect(lines, n -> sw(n,"67"), bl -> bl.solde());
        List<ReportLine> dotations   = collect(lines, n -> sw(n,"68"), bl -> bl.solde());
        List<ReportLine> impotBen    = collect(lines, n -> sw(n,"69"), bl -> bl.solde());

        List<ReportSection> charges = new ArrayList<>();
        addIfNotEmpty(charges, ReportSection.of("Achats et charges externes (60-61)", achats));
        addIfNotEmpty(charges, ReportSection.of("Autres services extérieurs (62)", services));
        addIfNotEmpty(charges, ReportSection.of("Impôts et taxes (63)", impots));
        addIfNotEmpty(charges, ReportSection.of("Charges de personnel (64)", personnel));
        addIfNotEmpty(charges, ReportSection.of("Autres charges d'exploitation (65)", autresCharE));
        addIfNotEmpty(charges, ReportSection.of("Dotations aux amortissements (68)", dotations));
        addIfNotEmpty(charges, ReportSection.of("Charges financières (66)", chargesFin));
        addIfNotEmpty(charges, ReportSection.of("Charges exceptionnelles (67)", chargesExc));
        addIfNotEmpty(charges, ReportSection.of("Impôt sur les bénéfices (69)", impotBen));

        BigDecimal totalCharges = sum(charges);

        // Résultats intermédiaires
        BigDecimal totalCharExploit = sumLines(achats, services, impots, personnel, autresCharE, dotations);
        BigDecimal totalCharFin     = sumList(chargesFin);
        BigDecimal totalCharExc     = sumList(chargesExc);

        BigDecimal resultatExploitation  = totalProdExploit.subtract(totalCharExploit);
        BigDecimal resultatFinancier     = totalProdFin.subtract(totalCharFin);
        BigDecimal resultatExceptionnel  = totalProdExc.subtract(totalCharExc);
        BigDecimal resultatNet           = totalProduits.subtract(totalCharges);

        return new CompteResultat(produits, totalProduits, charges, totalCharges,
                resultatExploitation, resultatFinancier, resultatExceptionnel, resultatNet);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static boolean sw(String number, String prefix) {
        return number != null && number.startsWith(prefix);
    }

    private List<ReportLine> collect(List<AccountingService.BalanceLine> lines,
                                      Predicate<String> filter,
                                      Function<AccountingService.BalanceLine, BigDecimal> amountFn) {
        return lines.stream()
                .filter(bl -> filter.test(bl.account().getNumber()))
                .map(bl -> new ReportLine(bl.account().getNumber(), bl.account().getLabel(), amountFn.apply(bl)))
                .filter(l -> l.amount().compareTo(BigDecimal.ZERO) != 0)
                .collect(Collectors.toList());
    }

    private List<ReportLine> collectPosSolde(List<AccountingService.BalanceLine> lines, Predicate<String> filter) {
        return lines.stream()
                .filter(bl -> filter.test(bl.account().getNumber()) && bl.solde().signum() > 0)
                .map(bl -> new ReportLine(bl.account().getNumber(), bl.account().getLabel(), bl.solde()))
                .collect(Collectors.toList());
    }

    private List<ReportLine> collectNegSolde(List<AccountingService.BalanceLine> lines, Predicate<String> filter) {
        return lines.stream()
                .filter(bl -> filter.test(bl.account().getNumber()) && bl.solde().signum() < 0)
                .map(bl -> new ReportLine(bl.account().getNumber(), bl.account().getLabel(), bl.solde().negate()))
                .collect(Collectors.toList());
    }

    private void addIfNotEmpty(List<ReportSection> sections, ReportSection s) {
        if (!s.lines().isEmpty()) sections.add(s);
    }

    private BigDecimal sum(List<ReportSection> sections) {
        return sections.stream().map(ReportSection::total).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sectionTotal(List<ReportSection> sections, String title) {
        return sections.stream().filter(s -> s.title().equals(title))
                .map(ReportSection::total).findFirst().orElse(BigDecimal.ZERO);
    }

    @SafeVarargs
    private BigDecimal sumLines(List<ReportLine>... lists) {
        BigDecimal t = BigDecimal.ZERO;
        for (List<ReportLine> l : lists) t = t.add(sumList(l));
        return t;
    }

    private BigDecimal sumList(List<ReportLine> lines) {
        return lines.stream().map(ReportLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // sumSections by partial title match — not needed, kept for clarity
    private BigDecimal sumSections(List<ReportSection> sections, String... titles) {
        BigDecimal t = BigDecimal.ZERO;
        for (String title : titles)
            t = t.add(sections.stream().filter(s -> s.title().contains(title))
                    .map(ReportSection::total).findFirst().orElse(BigDecimal.ZERO));
        return t;
    }
}
