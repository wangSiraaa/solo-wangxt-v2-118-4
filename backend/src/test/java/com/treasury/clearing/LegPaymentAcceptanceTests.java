package com.treasury.clearing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.treasury.clearing.domain.Claim;
import com.treasury.clearing.domain.ClaimStatus;
import com.treasury.clearing.domain.NettingAgreement;
import com.treasury.clearing.repo.ClaimRepository;
import com.treasury.clearing.repo.NettingAgreementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Acceptance for per-leg simulated payment on a CONFIRMED batch (classroom mode):
 * - two cash legs are registered one by one; the batch stays CONFIRMED until the
 *   last cash leg is paid, only then flips to PAID_SIMULATED;
 * - zero-amount set-off memo legs reject payment; unknown legs return 404;
 * - paying from SIMULATED is rejected and leaves the source invoices untouched;
 * - re-paying an already paid leg never rewrites its timestamp (idempotent);
 * - the one-shot endpoint and the per-leg route reach the same end state.
 *
 * The test runs on its own seeded agreements (NA-LEG / NA-LEG2) so it neither
 * depends on nor pollutes the demo dataset used by {@link ClearingApplicationTests}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LegPaymentAcceptanceTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private NettingAgreementRepository agreementRepository;
    @Autowired
    private ClaimRepository claimRepository;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void seedIsolatedRings() {
        seedRing("NA-LEG", "CLM-LG-", "INV-LG-");
        seedRing("NA-LEG2", "CLM-LG2-", "INV-LG2-");
    }

    /** Closed ring A->B 1000, B->C 600, C->A 400 -> cash legs A->B 400 and A->C 200 + memos. */
    private void seedRing(String agreement, String claimPrefix, String invoicePrefix) {
        if (agreementRepository.existsById(agreement)) {
            return;
        }
        NettingAgreement na = new NettingAgreement(agreement, "逐腿付款验收协议 " + agreement,
                true, false, "CNY", "A", LocalDate.of(2026, 1, 1), null, null, null);
        na.addMember("A");
        na.addMember("B");
        na.addMember("C");
        na.addCurrency("CNY");
        agreementRepository.save(na);
        claimRepository.saveAll(List.of(
                new Claim(claimPrefix + "001", invoicePrefix + "001", agreement, "A", "B",
                        new BigDecimal("1000.00"), "CNY", ClaimStatus.OPEN,
                        LocalDate.of(2026, 9, 10), LocalDate.of(2026, 10, 10), "逐腿验收环 1"),
                new Claim(claimPrefix + "002", invoicePrefix + "002", agreement, "B", "C",
                        new BigDecimal("600.00"), "CNY", ClaimStatus.OPEN,
                        LocalDate.of(2026, 9, 11), LocalDate.of(2026, 10, 11), "逐腿验收环 2"),
                new Claim(claimPrefix + "003", invoicePrefix + "003", agreement, "C", "A",
                        new BigDecimal("400.00"), "CNY", ClaimStatus.OPEN,
                        LocalDate.of(2026, 9, 12), LocalDate.of(2026, 10, 12), "逐腿验收环 3")));
    }

    @Test
    void perLegSimulatedPaymentLifecycle() throws Exception {
        JsonNode sim = simulate("NA-LEG");
        String batchId = sim.get("id").asText();
        assertThat(sim.get("status").asText()).isEqualTo("SIMULATED");
        assertThat(sim.get("payableLegCount").asInt()).isEqualTo(2);
        assertThat(sim.get("paidLegCount").asInt()).isEqualTo(0);

        JsonNode group = group(sim, "NA-LEG");
        List<JsonNode> cashLegs = cashLegs(group);
        assertThat(cashLegs).hasSize(2);
        JsonNode memoLeg = memoLeg(group);
        String leg1 = cashLegs.get(0).get("id").asText();
        String leg2 = cashLegs.get(1).get("id").asText();

        // Payment attempts on a SIMULATED batch are rejected, per leg and one-shot...
        mockMvc.perform(post("/api/batches/" + batchId + "/legs/" + leg1 + "/pay-simulated"))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/batches/" + batchId + "/pay-simulated"))
                .andExpect(status().isConflict());
        // ...and the source invoices stay fully untouched (traceability intact).
        JsonNode claims = claims();
        for (String id : List.of("CLM-LG-001", "CLM-LG-002", "CLM-LG-003")) {
            JsonNode c = findClaim(claims, id);
            assertThat(c.get("status").asText()).isEqualTo("OPEN");
            assertThat(c.get("offsetBatchId").asText()).isEmpty();
        }

        // Confirm the plan: now per-leg registration becomes possible.
        JsonNode confirmed = postJson("/api/batches/" + batchId + "/confirm");
        assertThat(confirmed.get("status").asText()).isEqualTo("CONFIRMED");

        // Zero-amount set-off memos carry no cash and cannot be paid; unknown leg 404s.
        mockMvc.perform(post("/api/batches/" + batchId + "/legs/"
                        + memoLeg.get("id").asText() + "/pay-simulated"))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/batches/" + batchId + "/legs/NO-SUCH-LEG/pay-simulated"))
                .andExpect(status().isNotFound());

        // First of two cash legs: batch is not finished yet and stays CONFIRMED.
        JsonNode afterFirst = postJson("/api/batches/" + batchId + "/legs/" + leg1 + "/pay-simulated");
        assertThat(afterFirst.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(afterFirst.get("paidLegCount").asInt()).isEqualTo(1);
        assertThat(afterFirst.get("payableLegCount").asInt()).isEqualTo(2);
        assertThat(afterFirst.get("paidSimulatedAt").asText()).isEmpty();
        String leg1PaidAt = leg(afterFirst, leg1).get("paidSimulatedAt").asText();
        assertThat(leg1PaidAt).isNotBlank();
        assertThat(leg(afterFirst, leg2).get("paidSimulatedAt").asText()).isEmpty();

        // Re-registering the same leg is idempotent: the timestamp must not move.
        Thread.sleep(1100);
        JsonNode repaid = postJson("/api/batches/" + batchId + "/legs/" + leg1 + "/pay-simulated");
        assertThat(leg(repaid, leg1).get("paidSimulatedAt").asText()).isEqualTo(leg1PaidAt);
        assertThat(repaid.get("status").asText()).isEqualTo("CONFIRMED");

        // Second cash leg completes the batch -> PAID_SIMULATED.
        JsonNode afterSecond = postJson("/api/batches/" + batchId + "/legs/" + leg2 + "/pay-simulated");
        assertThat(afterSecond.get("status").asText()).isEqualTo("PAID_SIMULATED");
        assertThat(afterSecond.get("paidLegCount").asInt()).isEqualTo(2);
        assertThat(afterSecond.get("paidSimulatedAt").asText()).isNotBlank();
        assertThat(leg(afterSecond, leg1).get("paidSimulatedAt").asText()).isEqualTo(leg1PaidAt);
        assertThat(leg(afterSecond, leg2).get("paidSimulatedAt").asText()).isNotBlank();

        // Even once the batch is PAID_SIMULATED, re-paying a leg stays a no-op.
        Thread.sleep(1100);
        JsonNode repaidAgain = postJson("/api/batches/" + batchId + "/legs/" + leg1 + "/pay-simulated");
        assertThat(leg(repaidAgain, leg1).get("paidSimulatedAt").asText()).isEqualTo(leg1PaidAt);

        // Invoice traceability is unaffected by the payment registrations.
        JsonNode claimsAfter = claims();
        for (String id : List.of("CLM-LG-001", "CLM-LG-002", "CLM-LG-003")) {
            JsonNode c = findClaim(claimsAfter, id);
            assertThat(c.get("status").asText()).isEqualTo("SETTLED");
            assertThat(c.get("offsetBatchId").asText()).isEqualTo(batchId);
        }

        // The one-shot entry and the per-leg route reach the same end state.
        JsonNode sim2 = simulate("NA-LEG2");
        String batch2 = sim2.get("id").asText();
        postJson("/api/batches/" + batch2 + "/confirm");
        JsonNode oneShot = postJson("/api/batches/" + batch2 + "/pay-simulated");
        assertThat(oneShot.get("status").asText()).isEqualTo("PAID_SIMULATED");
        assertThat(oneShot.get("payableLegCount").asInt()).isEqualTo(2);
        assertThat(oneShot.get("paidLegCount").asInt()).isEqualTo(2);
        for (JsonNode leg : group(oneShot, "NA-LEG2").get("legs")) {
            if (leg.get("amount").asDouble() > 0) {
                assertThat(leg.get("paidSimulatedAt").asText()).isNotBlank();
            } else {
                assertThat(leg.get("paidSimulatedAt").asText()).isEmpty();
            }
        }
    }

    private JsonNode simulate(String agreementCode) throws Exception {
        var req = json.createObjectNode()
                .put("valuationDate", "2026-09-30")
                .set("agreementCodes", json.createArrayNode().add(agreementCode));
        return json.readTree(mockMvc.perform(post("/api/batches/simulate")
                        .contentType("application/json")
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode postJson(String path) throws Exception {
        return json.readTree(mockMvc.perform(post(path))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode claims() throws Exception {
        return json.readTree(mockMvc.perform(get("/api/claims"))
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode group(JsonNode batch, String agreementCode) {
        for (JsonNode g : batch.get("groups")) {
            if (g.get("agreementCode").asText().equals(agreementCode)) {
                return g;
            }
        }
        throw new AssertionError("group missing: " + agreementCode);
    }

    private List<JsonNode> cashLegs(JsonNode group) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode leg : group.get("legs")) {
            if (leg.get("amount").asDouble() > 0) {
                out.add(leg);
            }
        }
        return out;
    }

    private JsonNode memoLeg(JsonNode group) {
        for (JsonNode leg : group.get("legs")) {
            if (leg.get("amount").asDouble() == 0 && !leg.get("isOriginal").asBoolean()) {
                return leg;
            }
        }
        throw new AssertionError("memo leg missing in group " + group.get("id").asText());
    }

    private JsonNode leg(JsonNode batch, String legId) {
        for (JsonNode g : batch.get("groups")) {
            for (JsonNode leg : g.get("legs")) {
                if (leg.get("id").asText().equals(legId)) {
                    return leg;
                }
            }
        }
        throw new AssertionError("leg missing: " + legId);
    }

    private JsonNode findClaim(JsonNode arr, String id) {
        for (JsonNode c : arr) {
            if (c.get("id").asText().equals(id)) {
                return c;
            }
        }
        throw new AssertionError("claim missing: " + id);
    }
}
