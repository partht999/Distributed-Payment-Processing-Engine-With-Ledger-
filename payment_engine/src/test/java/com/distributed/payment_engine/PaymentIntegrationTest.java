package com.distributed.payment_engine;

import com.distributed.payment_engine.model.dto.CreatePaymentRequest;
import com.distributed.payment_engine.model.dto.CreateWalletRequest;
import com.distributed.payment_engine.repository.LedgerRepository;
import com.distributed.payment_engine.repository.PaymentRepository;
import com.distributed.payment_engine.repository.TransactionRepository;
import com.distributed.payment_engine.repository.WalletRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Payment Processing Integration Tests
 *
 * These tests spin up the FULL Spring Boot application context
 * and hit the real REST API endpoints using MockMvc.
 * They use the actual PostgreSQL database (running in Docker)
 * to verify real persistence, Flyway migrations, and transactional behavior.
 *
 * IMPORTANT: Docker must be running with `docker compose up -d`
 * before executing these tests.
 *
 * Test execution order is enforced to build up test data progressively.
 * A @BeforeAll cleanup ensures tests are repeatable.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private WalletRepository walletRepo;

    @Autowired
    private PaymentRepository paymentRepo;

    @Autowired
    private TransactionRepository transactionRepo;

    @Autowired
    private LedgerRepository ledgerEntryRepo;

    // Fixed test wallet IDs
    private static final Long SENDER_WALLET_ID = 9001L;
    private static final Long RECEIVER_WALLET_ID = 9002L;
    private Long paymentId;

    /**
     * Clean up any leftover test data from previous runs.
     * This makes the tests fully repeatable.
     */
    @BeforeAll
    void cleanupTestData() {
        // Delete in order to respect foreign-key-like dependencies
        ledgerEntryRepo.deleteAll();
        transactionRepo.deleteAll();
        paymentRepo.deleteAll();
        walletRepo.deleteById(SENDER_WALLET_ID);
        walletRepo.deleteById(RECEIVER_WALLET_ID);
    }

    // ================================================================
    // TEST 1: Create Sender Wallet
    // ================================================================
    @Test
    @Order(1)
    @DisplayName("1. Create sender wallet with 10000 balance")
    void createSenderWallet() throws Exception {
        CreateWalletRequest req = new CreateWalletRequest();
        req.setWalletId(SENDER_WALLET_ID);
        req.setBalance(10000L);
        req.setUserId(1L);
        req.setPhoneNumber(9999999901L);

        mockMvc.perform(post("/api/v1/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.walletId").value(SENDER_WALLET_ID))
                .andExpect(jsonPath("$.balance").value(10000));
    }

    // ================================================================
    // TEST 2: Create Receiver Wallet
    // ================================================================
    @Test
    @Order(2)
    @DisplayName("2. Create receiver wallet with 5000 balance")
    void createReceiverWallet() throws Exception {
        CreateWalletRequest req = new CreateWalletRequest();
        req.setWalletId(RECEIVER_WALLET_ID);
        req.setBalance(5000L);
        req.setUserId(2L);
        req.setPhoneNumber(9999999902L);

        mockMvc.perform(post("/api/v1/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.walletId").value(RECEIVER_WALLET_ID))
                .andExpect(jsonPath("$.balance").value(5000));
    }

    // ================================================================
    // TEST 3: Create a P2P Payment
    // ================================================================
    @Test
    @Order(3)
    @DisplayName("3. Create P2P payment of 2000 from sender to receiver")
    void createPayment() throws Exception {
        CreatePaymentRequest req = new CreatePaymentRequest();
        req.setFromWalletId(SENDER_WALLET_ID);
        req.setToWalletId(RECEIVER_WALLET_ID);
        req.setAmount(2000L);
        req.setIdempotencyKey("integration-test-key-001");
        req.setPaymentType("P2P");

        MvcResult result = mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.amount").value(2000))
                .andExpect(jsonPath("$.fromWalletId").value(SENDER_WALLET_ID))
                .andExpect(jsonPath("$.toWalletId").value(RECEIVER_WALLET_ID))
                .andReturn();

        // Extract paymentId for subsequent tests
        String responseJson = result.getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(responseJson);
        paymentId = node.get("paymentId").asLong();
    }

    // ================================================================
    // TEST 4: Idempotency — Same key returns same payment
    // ================================================================
    @Test
    @Order(4)
    @DisplayName("4. Idempotency: duplicate key returns existing payment, not a new one")
    void idempotencyCheck() throws Exception {
        CreatePaymentRequest req = new CreatePaymentRequest();
        req.setFromWalletId(SENDER_WALLET_ID);
        req.setToWalletId(RECEIVER_WALLET_ID);
        req.setAmount(2000L);
        req.setIdempotencyKey("integration-test-key-001"); // Same key as Test 3
        req.setPaymentType("P2P");

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentId").value(paymentId)); // Same ID!
    }

    // ================================================================
    // TEST 5: Process the Payment
    // ================================================================
    @Test
    @Order(5)
    @DisplayName("5. Process payment — money moves, status becomes CAPTURED")
    void processPayment() throws Exception {
        mockMvc.perform(post("/api/v1/payments/" + paymentId + "/process"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"));
    }

    // ================================================================
    // TEST 6: Verify Wallet Balances After Transfer
    // ================================================================
    @Test
    @Order(6)
    @DisplayName("6. Sender balance decreased to 8000, receiver increased to 7000")
    void verifyBalancesAfterTransfer() throws Exception {
        // Sender: 10000 - 2000 = 8000
        mockMvc.perform(get("/api/v1/wallets/" + SENDER_WALLET_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(8000));

        // Receiver: 5000 + 2000 = 7000
        mockMvc.perform(get("/api/v1/wallets/" + RECEIVER_WALLET_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(7000));
    }

    // ================================================================
    // TEST 7: Re-process already CAPTURED payment (idempotent)
    // ================================================================
    @Test
    @Order(7)
    @DisplayName("7. Re-processing CAPTURED payment returns idempotently (no double-charge)")
    void reprocessCapturedPayment() throws Exception {
        mockMvc.perform(post("/api/v1/payments/" + paymentId + "/process"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"));

        // Balance should NOT have changed
        mockMvc.perform(get("/api/v1/wallets/" + SENDER_WALLET_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(8000));
    }

    // ================================================================
    // TEST 8: Refund the Payment
    // ================================================================
    @Test
    @Order(8)
    @DisplayName("8. Refund payment — money moves back, status becomes REFUNDED")
    void refundPayment() throws Exception {
        mockMvc.perform(post("/api/v1/payments/" + paymentId + "/refund"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));
    }

    // ================================================================
    // TEST 9: Verify Balances Restored After Refund
    // ================================================================
    @Test
    @Order(9)
    @DisplayName("9. After refund: sender back to 10000, receiver back to 5000")
    void verifyBalancesAfterRefund() throws Exception {
        mockMvc.perform(get("/api/v1/wallets/" + SENDER_WALLET_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(10000));

        mockMvc.perform(get("/api/v1/wallets/" + RECEIVER_WALLET_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(5000));
    }

    // ================================================================
    // TEST 10: Cannot re-process a REFUNDED payment 
    // ================================================================
    @Test
    @Order(10)
    @DisplayName("10. Cannot re-process a REFUNDED payment — returns error")
    void cannotReprocessRefundedPayment() throws Exception {
        mockMvc.perform(post("/api/v1/payments/" + paymentId + "/process"))
                .andExpect(status().isConflict());
    }

    // ================================================================
    // TEST 11: Payment not found
    // ================================================================
    @Test
    @Order(11)
    @DisplayName("11. GET non-existent payment returns 404")
    void paymentNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/payments/999999"))
                .andExpect(status().isNotFound());
    }

    // ================================================================
    // TEST 12: List all payments
    // ================================================================
    @Test
    @Order(12)
    @DisplayName("12. GET /api/v1/payments returns a non-empty list")
    void listAllPayments() throws Exception {
        mockMvc.perform(get("/api/v1/payments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }
}
