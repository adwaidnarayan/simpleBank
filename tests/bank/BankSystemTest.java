package bank;

import java.io.IOException;
import java.nio.file.*;

/** Dependency-free regression tests using temporary data, never the application's accounts. */
public final class BankSystemTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++; System.out.println("PASS: " + message);
    }
    private interface Action { void run() throws Exception; }
    private static void rejects(Action action, String message) throws Exception {
        try { action.run(); } catch (IllegalArgumentException expected) { check(true, message); return; }
        throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("bank-test-");
        Path file = dir.resolve("bank.dat");
        try {
            BankSystem bank = new BankSystem(new FileBankRepository(file));
            check(bank.accounts().isEmpty(), "Fresh bank contains no sample accounts");
            String a = bank.openAccount("", "Alice", "alice@example.com", "1000", "500", "9876543210");
            String b = bank.openAccount("C1", "", "", "500", "100", "9876543210");
            check(bank.customers().size() == 1 && bank.customers().get(0).accountNumbers().size() == 2, "One customer owns multiple accounts");
            check(bank.history(a).size() == 1, "Opening deposit is logged");
            bank.transact("deposit", a, "", "250.25");
            check(bank.account(a).balance().toPlainString().equals("1250.25"), "Deposit uses exact decimal arithmetic");
            bank.transact("withdraw", a, "", "750.25");
            check(bank.account(a).balance().toPlainString().equals("500.00"), "Withdrawal to exact minimum succeeds");
            int count = bank.history(a).size();
            rejects(() -> bank.transact("withdraw", a, "", "0.01"), "Withdrawal below minimum is rejected");
            check(bank.history(a).size() == count && bank.account(a).balance().toPlainString().equals("500.00"), "Rejected withdrawal leaves balance and history unchanged");
            bank.transact("transfer", b, a, "400");
            check(bank.account(a).balance().toPlainString().equals("900.00") && bank.account(b).balance().toPlainString().equals("100.00"), "Transfer updates both accounts");
            check(bank.history(a).get(0).id() == bank.history(b).get(0).id(), "Transfer appears in both histories with same ID");
            rejects(() -> bank.transact("transfer", b, a, "1"), "Transfer below source minimum is rejected");
            check(bank.account(a).balance().toPlainString().equals("900.00"), "Rejected transfer does not credit recipient");
            rejects(() -> bank.transact("transfer", a, a, "1"), "Self-transfer is rejected");
            rejects(() -> bank.transact("transfer", a, "missing", "1"), "Missing destination is rejected");
            rejects(() -> bank.transact("deposit", "missing", "", "1"), "Missing account is rejected");
            for (String invalid : new String[]{"0", "-1", "1.001", "NaN", "1e3", "", "1000000000000"})
                rejects(() -> bank.transact("deposit", a, "", invalid), "Invalid amount rejected: '" + invalid + "'");
            rejects(() -> bank.openAccount("", "Bob", "bob@example.com", "99", "100", "9876543210"), "Insufficient opening deposit is rejected");
            rejects(() -> bank.openAccount("", "Bob", "bad-email", "100", "0", "9876543210"), "Invalid email is rejected");
            rejects(() -> bank.openAccount("", " ", "bob@example.com", "100", "0", "9876543210"), "Blank name is rejected");
            rejects(() -> bank.openAccount("", "Alice", "ALICE@example.com", "100", "0", "9876543210"), "Duplicate customer email is rejected");
            rejects(() -> bank.openAccount("missing", "", "", "100", "0", "9876543210"), "Missing customer is rejected");
            String zero = bank.openAccount("", "Bob", "bob@example.com", "0", "0", "9876543210");
            check(bank.history(zero).isEmpty(), "Zero-balance account opens without a fictitious transaction");
            BankSystem restored = new BankSystem(new FileBankRepository(file));
            Customer alice = restored.customers().get(0), bob = restored.customers().get(1);
            check(!alice.cardNumber().equals(bob.cardNumber()) && alice.cardNumber().matches("[1-9][0-9]{11}"), "Different customers receive distinct 12-digit cards");
            rejects(() -> restored.transactWithCard("deposit", a, "", "1", "Bob", bob.cardNumber(), "", ""), "Another customer's card cannot operate account");
            rejects(() -> restored.transactWithCard("transfer", a, zero, "1", alice.name(), alice.cardNumber(), "Bob", alice.cardNumber()), "Transfer requires matching recipient credentials");
            rejects(() -> restored.openAccount("", "Invalid Phone", "phone@example.com", "0", "0", "abc"), "Malformed phone rejected");
            check(restored.customers().get(0).cardNumber().equals(bank.customers().get(0).cardNumber()), "Card is stable across restarts");
            check(restored.account(a).balance().toPlainString().equals("900.00") && restored.history(a).size() == 4, "Balances and history survive restart");
            BankRepository failing = new BankRepository() {
                public BankSystem.State load() throws IOException { return new FileBankRepository(file).load(); }
                public void save(BankSystem.State s) throws IOException { throw new IOException("Simulated disk failure"); }
            };
            BankSystem failureBank = new BankSystem(failing);
            try { failureBank.transact("transfer", a, b, "100"); throw new AssertionError("Expected save failure"); } catch (IOException expected) { }
            check(failureBank.account(a).balance().toPlainString().equals("900.00") && failureBank.account(b).balance().toPlainString().equals("100.00") && failureBank.history(a).size() == 4, "Save failure rolls back transfer and log");
            try { failureBank.openAccount("C1", "", "", "100", "0", "9876543210"); throw new AssertionError("Expected save failure"); } catch (IOException expected) { }
            check(failureBank.accounts().size() == 3 && failureBank.customers().get(0).accountNumbers().size() == 2, "Save failure rolls back account creation");
            Files.writeString(dir.resolve("bad.dat"), "corrupt");
            try { new BankSystem(new FileBankRepository(dir.resolve("bad.dat"))); throw new AssertionError("Expected corrupt data failure"); } catch (IOException expected) { check(true, "Corrupt data is not silently overwritten"); }
            System.out.println("All " + checks + " checks passed.");
        } finally {
            try (var paths = Files.list(dir)) { for (Path p : paths.toList()) Files.deleteIfExists(p); }
            Files.deleteIfExists(dir);
        }
    }
}
