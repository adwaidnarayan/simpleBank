package bank;

import java.io.Serializable;
import java.math.BigDecimal;

/** An account encapsulates its balance and enforces its minimum balance. */
public final class Account implements Serializable {
    private static final long serialVersionUID = 1L;
    private final String number, customerId;
    private String holder;
    private BigDecimal minimumBalance;
    private boolean closed, frozen;
    private BigDecimal balance;
    public Account(String number, String customerId, String holder, BigDecimal balance, BigDecimal minimum) {
        this.number = number; this.customerId = customerId; this.holder = holder;
        this.balance = balance; this.minimumBalance = minimum;
    }
    public String number() { return number; }
    public String customerId() { return customerId; }
    public String holder() { return holder; }
    public BigDecimal balance() { return balance; }
    public BigDecimal minimumBalance() { return minimumBalance; }
    public String status() { return closed ? "Closed" : frozen ? "Frozen" : "Active"; }
    void holder(String name) { holder = name; }
    void configure(BigDecimal minimum, String status) {
        if (minimum.compareTo(balance) > 0) throw new IllegalArgumentException("Minimum balance cannot exceed the current balance.");
        if (!java.util.Set.of("Active", "Frozen", "Closed").contains(status)) throw new IllegalArgumentException("Invalid account status.");
        if (closed && !status.equals("Closed")) throw new IllegalArgumentException("Closed accounts cannot be reopened.");
        if (status.equals("Closed") && balance.signum() != 0) throw new IllegalArgumentException("Withdraw or transfer the remaining funds before closing this account. Balance must be zero.");
        minimumBalance = minimum; closed = status.equals("Closed"); frozen = status.equals("Frozen");
    }
    private void requireActive() {
        if (closed || frozen) throw new IllegalArgumentException("Transactions are not allowed on " + status().toLowerCase() + " accounts.");
    }
    void credit(BigDecimal amount) { requireActive(); balance = balance.add(amount); }
    void debit(BigDecimal amount) {
        requireActive();
        if (balance.subtract(amount).compareTo(minimumBalance) < 0)
            throw new IllegalArgumentException("Transaction rejected: the remaining balance must be at least INR " + minimumBalance + ".");
        balance = balance.subtract(amount);
    }
}
