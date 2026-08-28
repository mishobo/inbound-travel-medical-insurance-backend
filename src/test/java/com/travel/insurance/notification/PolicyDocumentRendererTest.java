package com.travel.insurance.notification;

import com.travel.insurance.notification.PolicyDocumentData.BenefitLine;
import com.travel.insurance.visitor.Gender;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.templatemode.TemplateMode;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyDocumentRendererTest {

    private PolicyDocumentRenderer newRenderer() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        SpringTemplateEngine templateEngine = new SpringTemplateEngine();
        templateEngine.setTemplateResolver(resolver);
        return new PolicyDocumentRenderer(templateEngine);
    }

    private PolicyDocumentData sampleData(List<BenefitLine> benefits) {
        return sampleData(benefits, null, null);
    }

    private PolicyDocumentData sampleData(List<BenefitLine> benefits, String underwriterLogoUrl) {
        return sampleData(benefits, underwriterLogoUrl, null);
    }

    private PolicyDocumentData sampleData(List<BenefitLine> benefits, String underwriterLogoUrl,
                                           String esignatureUrl) {
        return new PolicyDocumentData(
                "Jane Traveler",
                "P1234567",
                "ACME-2026-000123",
                LocalDate.of(1990, 5, 12),
                Gender.FEMALE,
                "Germany",
                "12 Example Street, Berlin",
                "jane.traveler@example.com",
                "+254700000000",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 11, 1),
                LocalDate.of(2027, 8, 1),
                "Tourism",
                List.of("Acme Insurance"),
                underwriterLogoUrl,
                esignatureUrl,
                benefits,
                "+254 700 000000",
                "assistance@example.com");
    }

    @Test
    void rendersHtmlWithKeyFields() {
        PolicyDocumentRenderer renderer = newRenderer();
        PolicyDocumentData data = sampleData(List.of(
                new BenefitLine("Medical Expenses", new BigDecimal("20000.00"))));

        String html = renderer.renderHtml(data);

        assertThat(html).contains("Jane Traveler");
        assertThat(html).contains("P1234567");
        assertThat(html).contains("Medical Expenses");
        assertThat(html).contains("20,000");
        assertThat(html).contains("Acme Insurance");
        assertThat(html).contains("Mandatory Inbound Travel Health Insurance");
        assertThat(html).doesNotContain("CERTIFICATE OF INSURANCE");
        assertThat(html).doesNotContain("Kenya CARES Inbound Cover");
        assertThat(html).contains("Female");
        assertThat(html).doesNotContain("underlyingConditions");
    }

    @Test
    void coverPeriodRunsFromDateInToPolicyExpiryDate() {
        PolicyDocumentRenderer renderer = newRenderer();
        PolicyDocumentData data = sampleData(List.of(
                new BenefitLine("Medical Expenses", new BigDecimal("20000.00"))));

        String html = renderer.renderHtml(data);

        assertThat(html).contains("01 Aug 2026 — 01 Aug 2027 (365 days)");
        assertThat(html).doesNotContain("01 Nov 2026");
    }

    @Test
    void rendersCertificateSerialNumberAndOmitsVerificationCopy() {
        PolicyDocumentRenderer renderer = newRenderer();
        PolicyDocumentData data = sampleData(List.of(
                new BenefitLine("Medical Expenses", new BigDecimal("20000.00"))));

        String html = renderer.renderHtml(data);

        assertThat(html).contains("Serial Number");
        assertThat(html).contains("ACME-2026-000123");
        assertThat(html).doesNotContain("Verify this certificate");
        assertThat(html).doesNotContain("Policy Number above");
        assertThat(html).doesNotContain("scanning the QR code");
    }

    @Test
    void omitsGovernmentBrandingFromMasthead() {
        PolicyDocumentRenderer renderer = newRenderer();
        PolicyDocumentData data = sampleData(List.of(
                new BenefitLine("Medical Expenses", new BigDecimal("20000.00"))));

        String html = renderer.renderHtml(data);

        assertThat(html).doesNotContain("gok-logo");
        assertThat(html).doesNotContain("REPUBLIC OF KENYA");
        assertThat(html).doesNotContain("MINISTRY OF HEALTH");
        assertThat(html).doesNotContain("KENYA CARES</div>");
        assertThat(html).contains("class=\"masthead\"");
    }

    @Test
    void rendersUnderwriterLogoWhenUrlProvided() {
        PolicyDocumentRenderer renderer = newRenderer();
        PolicyDocumentData data = sampleData(
                List.of(new BenefitLine("Medical Expenses", new BigDecimal("20000.00"))),
                "https://cdn.example/acme.png");

        String html = renderer.renderHtml(data);

        assertThat(html).contains("src=\"https://cdn.example/acme.png\"");
        assertThat(html).doesNotContain("[ UNDERWRITER LOGO ]");
    }

    @Test
    void rendersLogoPlaceholderWhenNoUrl() {
        PolicyDocumentRenderer renderer = newRenderer();
        PolicyDocumentData data = sampleData(
                List.of(new BenefitLine("Medical Expenses", new BigDecimal("20000.00"))));

        String html = renderer.renderHtml(data);

        assertThat(html).contains("[ UNDERWRITER LOGO ]");
    }

    @Test
    void rendersEsignatureImageWhenUrlProvided() {
        PolicyDocumentRenderer renderer = newRenderer();
        PolicyDocumentData data = sampleData(
                List.of(new BenefitLine("Medical Expenses", new BigDecimal("20000.00"))),
                null, "https://cdn.example/acme-signature.png");

        String html = renderer.renderHtml(data);

        assertThat(html).contains("src=\"https://cdn.example/acme-signature.png\"");
        assertThat(html).contains("Digitally signed by the underwriter");
        assertThat(html).doesNotContain("No wet signature required");
    }

    @Test
    void rendersDefaultSignatureCopyWhenNoEsignatureUrl() {
        PolicyDocumentRenderer renderer = newRenderer();
        PolicyDocumentData data = sampleData(
                List.of(new BenefitLine("Medical Expenses", new BigDecimal("20000.00"))));

        String html = renderer.renderHtml(data);

        assertThat(html).contains("No wet signature required");
        assertThat(html).doesNotContain("class=\"esign-img\"");
    }

    @Test
    void omitsCumulativeBenefitLimitRow() {
        PolicyDocumentRenderer renderer = newRenderer();
        PolicyDocumentData data = sampleData(List.of(
                new BenefitLine("Medical Expenses", new BigDecimal("20000.00")),
                new BenefitLine("Emergency Evacuation", new BigDecimal("30000.00"))));

        String html = renderer.renderHtml(data);

        assertThat(html).doesNotContain("Cumulative benefit limit");
    }

    @Test
    void rendersPlaceholderWhenNoBenefitsAssigned() {
        PolicyDocumentRenderer renderer = newRenderer();
        PolicyDocumentData data = sampleData(List.of());

        String html = renderer.renderHtml(data);

        assertThat(html).contains("No benefits assigned yet.");
    }

    @Test
    void rendersPdfStartingWithPdfMagicHeader() {
        PolicyDocumentRenderer renderer = newRenderer();
        PolicyDocumentData data = sampleData(List.of(
                new BenefitLine("Prescribed Medicines", new BigDecimal("300.00"))));

        byte[] pdf = renderer.renderPdf(data);

        assertThat(pdf.length).isGreaterThan(4);
        assertThat(new String(pdf, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
    }

    @Test
    void rendersAsSinglePageForARealisticBenefitSchedule() throws IOException {
        PolicyDocumentRenderer renderer = newRenderer();
        PolicyDocumentData data = sampleData(List.of(
                new BenefitLine("Medical Expenses", new BigDecimal("20000.00")),
                new BenefitLine("Emergency Medical Transportation/Evacuation", new BigDecimal("25000.00")),
                new BenefitLine("Prescribed Medicines", new BigDecimal("300.00")),
                new BenefitLine("Mental Illness", new BigDecimal("1000.00")),
                new BenefitLine("Repatriation of Mortal Remains", new BigDecimal("5000.00"))));

        byte[] pdf = renderer.renderPdf(data);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
        }
    }
}
