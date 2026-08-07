# Part 3: Immutable Ledger & Double-Entry Bookkeeping

> This is the feature that makes this project stand out on a resume. Every interviewer asks: "How do you ensure financial correctness?"

---

## The Problem with Traditional Balance Updates

Most beginner projects do this:

```sql
-- ❌ BAD: Direct balance update
UPDATE wallets SET balance = balance - 500 WHERE wallet_id = 1;
UPDATE wallets SET balance = balance + 500 WHERE wallet_id = 2;
```

**Why this is dangerous:**
1. **History is lost** — After the UPDATE, you can't tell what the balance was yesterday
2. **Silent corruption** — If the second UPDATE fails (crash, timeout), Wallet 1 lost money but Wallet 2 didn't receive it. Money vanished.
3. **No audit trail** — Regulators need to see every financial event. "Show me all transactions for this wallet" is impossible.
4. **Race conditions** — Two concurrent UPDATEs can read the same balance and both subtract from it

---

## The Solution: Append-Only Ledger

Instead of updating balances, we **append entries** to an immutable log:

```
ledger_entries table:
┌─────────┬───────────┬────────┬───────────┬───────────┐
│ entryId │ walletId  │ amount │ entryType │ paymentId │
├─────────┼───────────┼────────┼───────────┼───────────┤
│ 1       │ 1         │ 10000  │ CREDIT    │ NULL      │  ← Initial deposit
│ 2       │ 2         │ 5000   │ CREDIT    │ NULL      │  ← Initial deposit
│ 3       │ 1         │ 500    │ DEBIT     │ 101       │  ← Sent ₹500
│ 4       │ 2         │ 500    │ CREDIT    │ 101       │  ← Received ₹500
│ 5       │ 2         │ 500    │ DEBIT     │ 101       │  ← Refund (reversed)
│ 6       │ 1         │ 500    │ CREDIT    │ 101       │  ← Refund (money back)
└─────────┴───────────┴────────┴───────────┴───────────┘
```

**Key rules:**
1. **Entries are NEVER updated or deleted** — only appended
2. **Every transfer creates exactly ONE DEBIT + ONE CREDIT** (balanced)
3. **Balance is DERIVED** from history: `SUM(CREDITs) - SUM(DEBITs)`
4. **Every entry links to a paymentId** for traceability

---

## How Balance Is Computed

The `wallet.balance` column is a **cache** — a convenience value for fast reads. The real balance comes from the ledger:

```java
// WalletService.java
public Long getLedgerDerivedBalance(Long walletId) {
    long balance = 0;
    for (LedgerEntry entry : ledgerRepo.findByWalletId(walletId)) {
        if (entry.getEntryType() == EntryType.CREDIT) {
            balance += entry.getAmount();
        } else if (entry.getEntryType() == EntryType.DEBIT) {
            balance -= entry.getAmount();
        }
    }
    return balance;
}
```

**For Wallet 1 after the transfer and refund:**
```
CREDIT +10000 (initial deposit)
DEBIT  -500   (sent to Wallet 2)
CREDIT +500   (refund received back)
────────────
= 10000       ← This is the ledger-derived balance
```

The API endpoint `/api/v1/wallets/1/balance` returns BOTH:
```json
{
  "walletId": 1,
  "walletBalance": 10000,     // ← cached column
  "ledgerDerivedBalance": 10000  // ← computed from ledger
}
```

If these two numbers ever disagree, you have a bug. The reconciliation service checks for this.

---

## Double-Entry Bookkeeping

This is the system used by every bank and financial institution for 500+ years.

**The Rule:** For every DEBIT, there must be an equal CREDIT. Money is never created or destroyed.

### Transfer: Wallet 1 → Wallet 2 (₹500)

```
Entry 1: DEBIT  Wallet 1  ₹500  (money leaves)
Entry 2: CREDIT Wallet 2  ₹500  (money arrives)
```

Total DEBITs = 500, Total CREDITs = 500 → **Balanced** ✅

### Refund: Wallet 2 → Wallet 1 (₹500)

```
Entry 3: DEBIT  Wallet 2  ₹500  (money leaves)
Entry 4: CREDIT Wallet 1  ₹500  (money arrives)
```

Total DEBITs = 1000, Total CREDITs = 1000 → **Still balanced** ✅

Notice: We did NOT delete entries 1 and 2. We created NEW compensating entries. This is how real accounting works.

---

## How Transfer Works in Code

```java
// WalletService.java → transfer()
public boolean transfer(Long fromId, Long toId, Long amount, Long paymentId) {
    
    // 1. Load wallets
    Wallet from = walletRepo.findById(fromId).orElse(null);
    Wallet to = walletRepo.findById(toId).orElse(null);
    
    // 2. Validate
    if (from.getStatus() != WalletStatus.ACTIVE) throw ...;
    if (to.getStatus() != WalletStatus.ACTIVE) throw ...;
    if (amount <= 0) throw ...;
    
    // 3. Check balance FROM THE LEDGER (not the cached column!)
    Long ledgerBalance = getLedgerDerivedBalance(fromId);
    if (ledgerBalance < amount) throw new InsufficientAmountException(...);
    
    // 4. Update cached balances (convenience)
    from.setBalance(from.getBalance() - amount);
    to.setBalance(to.getBalance() + amount);
    walletRepo.save(from);
    walletRepo.save(to);
    
    // 5. Record the transaction
    Transaction txn = new Transaction(null, fromId, toId, amount, 
                                       TransactionType.TRANSFER, paymentId);
    txnRepo.save(txn);
    
    // 6. CRITICAL: Write the ledger entries (immutable truth)
    LedgerEntry debit = new LedgerEntry(null, fromId, amount, EntryType.DEBIT, paymentId);
    LedgerEntry credit = new LedgerEntry(null, toId, amount, EntryType.CREDIT, paymentId);
    ledgerRepo.save(debit);
    ledgerRepo.save(credit);
    
    return true;
}
```

**Important:** Steps 4, 5, and 6 all happen inside `@Transactional`. If any step fails, ALL of them roll back. You can never have a DEBIT without its matching CREDIT.

---

## Why Amounts Are Stored as Integers

```java
private Long amount;  // ← stored in smallest currency unit
```

We store ₹500 as `500` (paise), not as `5.00` (rupees).

**Why?**
- `0.1 + 0.2 = 0.30000000000000004` in floating point ← **UNACCEPTABLE for money**
- Integers have perfect precision: `10 + 20 = 30` always
- This is what Stripe does: `amount: 5000` means $50.00
- Display formatting (₹5.00) happens in the frontend, never in the backend

---

## The Reconciliation Engine

The `ReconciliationService` runs 3 checks to prove the ledger is correct:

### Check 1: Double-Entry Balance
```
Total DEBITs across ALL wallets MUST equal Total CREDITs
(excluding system credits like initial deposits)
```
If they don't match → money was created or destroyed → critical bug.

### Check 2: Wallet Balance vs Ledger
```
For each wallet:
  wallet.balance (cached column) == SUM(CREDITs) - SUM(DEBITs) from ledger
```
If they don't match → cache coherence bug (a transaction updated the wallet but didn't write a ledger entry).

### Check 3: Orphan Detection
```
Every ledger entry must reference a valid walletId
```
Orphans indicate referential integrity violations.

**API:** `GET /api/v1/reconciliation`

```json
{
  "overallStatus": "HEALTHY",
  "doubleEntryBalance": { "passed": true, "totalDebits": 1500, "totalCredits": 1500 },
  "walletBalanceVerification": { "passed": true, "walletsVerified": 2 },
  "orphanDetection": { "passed": true, "orphanCount": 0 }
}
```

---

## Interview Talking Points

**Q: "How does your ledger work?"**

> "We use an append-only, immutable double-entry ledger. Every financial event creates a DEBIT and a CREDIT entry. Entries are never updated or deleted. The wallet balance column is just a cache — the true balance is derived from SUM(CREDITs) - SUM(DEBITs). Refunds create new compensating entries rather than deleting old ones. This gives us a complete audit trail and makes it trivially easy to reconstruct the balance at any point in time."

**Q: "How do you know the ledger is correct?"**

> "We built a reconciliation engine that runs three checks: (1) total DEBITs equal total CREDITs across the system, (2) each wallet's cached balance matches its ledger-derived balance, and (3) no orphan entries exist. This is the same kind of daily batch check that Stripe runs."

**Q: "Why not just update the balance directly?"**

> "Direct updates lose history, have no audit trail, and can silently corrupt data if a crash happens between two UPDATEs. With an immutable ledger, every financial event is permanently recorded, corrections happen via compensating entries, and you can reconstruct any wallet's balance at any point in time."
