package bank;

import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

/** Coordinates customers, accounts and transactions as synchronized, durable operations. */
public final class BankSystem {
    public static final class State implements Serializable {
        private static final long serialVersionUID = 1L;
        final Map<String, Customer> customers = new LinkedHashMap<>();
        final Map<String, Account> accounts = new LinkedHashMap<>();
        final List<Transaction> transactions = new ArrayList<>();
        List<String> adminLog = new ArrayList<>();
        long nextCustomer = 1, nextAccount = 100001, nextTransaction = 1;
    }
    private final BankRepository repository;
    private State state;
    private final java.security.SecureRandom random = new java.security.SecureRandom();
    public BankSystem(BankRepository repository) throws IOException {
        this.repository = repository; state = repository.load();
        if (state.adminLog == null) state.adminLog = new ArrayList<>(); // Upgrade existing saved banks.
        boolean migrated = false;
        for (Customer c : state.customers.values()) if (c.cardNumber() == null) {
            c.issueCard(newCard()); migrated = true;
        }
        if (migrated) repository.save(state);
    }
    private String newCard() {
        String candidate;
        do {
            StringBuilder digits = new StringBuilder().append(1 + random.nextInt(9));
            for (int i = 1; i < 12; i++) digits.append(random.nextInt(10));
            candidate = digits.toString();
        } while (cardExists(candidate));
        return candidate;
    }
    private boolean cardExists(String card) { return state.customers.values().stream().anyMatch(c -> card.equals(c.cardNumber())); }
    private static String validatePhone(String phone) {
        phone = phone == null ? "" : phone.trim();
        if (!phone.matches("\\+?[0-9]{10,15}")) throw new IllegalArgumentException("Enter a phone number with 10 to 15 digits, optionally starting with +.");
        return phone;
    }
    /** Credentials must belong to the owner of the selected account; no name-only lookup. */
    private void verifyCard(String accountNumber, String name, String card) {
        Account account = account(accountNumber);
        Customer customer = state.customers.get(account.customerId());
        if (name == null || card == null || !card.matches("[0-9]{12}") || !card.equals(customer.cardNumber()) || !customer.name().equalsIgnoreCase(name.trim()))
            throw new IllegalArgumentException("The full name and 12-digit card number must match the selected account's customer.");
    }
    public synchronized void transactWithCard(String type, String source, String destination, String amount,
            String name, String card, String recipientName, String recipientCard) throws IOException {
        verifyCard(source, name, card);
        if ("transfer".equals(type)) verifyCard(destination, recipientName, recipientCard);
        transact(type, source, destination, amount);
    }
    public synchronized List<String> adminLog() {
        List<String> result = new ArrayList<>(state.adminLog); Collections.reverse(result); return List.copyOf(result);
    }
    /** Update identity details together with every linked account holder, with durable rollback. */
    public synchronized void updateCustomer(String id, String name, String email, String phone) throws IOException {
        phone = validatePhone(phone);
        name = name == null ? "" : name.trim(); email = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (name.isBlank() || name.length() > 100) throw new IllegalArgumentException("Enter a customer name of 1 to 100 characters.");
        if (email.length() > 254 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw new IllegalArgumentException("Enter a valid email address.");
        Customer customer = state.customers.get(id);
        if (customer == null) throw new IllegalArgumentException("Customer not found.");
        for (Customer c : state.customers.values()) if (!c.id().equals(id) && c.email().equalsIgnoreCase(email))
            throw new IllegalArgumentException("Email already belongs to another customer.");
        State backup = copy();
        try {
            String old = customer.name() + " <" + customer.email() + ">";
            customer.update(name, email);
            customer.phone(phone);
            for (String number : customer.accountNumbers()) account(number).holder(name);
            state.adminLog.add(Instant.now() + " | Updated " + id + ": " + old + " → " + name + " <" + email + ">");
            repository.save(state);
        } catch (IOException | RuntimeException e) { state = backup; throw e; }
    }
    public synchronized void updateAccount(String number, String minimum, String status) throws IOException {
        BigDecimal min = money(minimum, true); State backup = copy();
        try {
            Account account = account(number);
            String old = account.status() + ", minimum INR " + account.minimumBalance();
            account.configure(min, status);
            state.adminLog.add(Instant.now() + " | Updated " + number + ": " + old + " → " + status + ", minimum INR " + min);
            repository.save(state);
        } catch (IOException | RuntimeException e) { state = backup; throw e; }
    }
    public static BigDecimal money(String text, boolean allowZero) {
        try {
            if (text == null || !text.matches("[0-9]{1,12}(\\.[0-9]{1,2})?")) throw new NumberFormatException();
            BigDecimal value = new BigDecimal(text).setScale(2, RoundingMode.UNNECESSARY);
            if (value.signum() < 0 || (!allowZero && value.signum() == 0)) throw new NumberFormatException();
            return value;
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException("Enter a " + (allowZero ? "non-negative" : "positive") + " amount with up to 12 digits and 2 decimal places.");
        }
    }
    public synchronized List<Account> accounts() { return List.copyOf(state.accounts.values()); }
    public synchronized List<Customer> customers() { return List.copyOf(state.customers.values()); }
    public synchronized Account account(String number) {
        Account account = state.accounts.get(number);
        if (account == null) throw new IllegalArgumentException("Account not found.");
        return account;
    }
    public synchronized List<Transaction> history(String number) {
        account(number);
        return state.transactions.stream().filter(t -> number.equals(t.source()) || number.equals(t.destination()))
            .sorted(Comparator.comparingLong(Transaction::id).reversed()).toList();
    }
    public synchronized String openAccount(String customerId, String name, String email, String opening, String minimum, String phone) throws IOException {
        BigDecimal initial = money(opening, true), min = money(minimum, true);
        if (initial.compareTo(min) < 0) throw new IllegalArgumentException("Opening deposit must meet the minimum balance.");
        State backup = copy();
        try {
            Customer customer;
            if (customerId != null && !customerId.isBlank()) {
                customer = state.customers.get(customerId);
                if (customer == null) throw new IllegalArgumentException("Customer not found.");
            } else {
                phone = validatePhone(phone);
                name = name == null ? "" : name.trim(); email = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
                if (name.isBlank() || name.length() > 100) throw new IllegalArgumentException("Enter a customer name of 1 to 100 characters.");
                if (email.length() > 254 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw new IllegalArgumentException("Enter a valid email address.");
                for (Customer existing : state.customers.values()) if (existing.email().equalsIgnoreCase(email))
                    throw new IllegalArgumentException("That email already belongs to a customer. Select the existing customer.");
                String id = "C" + state.nextCustomer++;
                customer = new Customer(id, name, email); state.customers.put(id, customer);
                customer.phone(phone); customer.issueCard(newCard());
            }
            String number = "A" + state.nextAccount++;
            Account account = new Account(number, customer.id(), customer.name(), initial, min);
            state.accounts.put(number, account); customer.addAccount(number);
            if (initial.signum() > 0) log(Transaction.Type.DEPOSIT, initial, null, account);
            repository.save(state); return number;
        } catch (IOException | RuntimeException e) { state = backup; throw e; }
    }
    synchronized void transact(String type, String source, String destination, String amountText) throws IOException {
        BigDecimal amount = money(amountText, false);
        State backup = copy();
        try {
            switch (type) {
                case "deposit" -> { Account to = account(source); to.credit(amount); log(Transaction.Type.DEPOSIT, amount, null, to); }
                case "withdraw" -> { Account from = account(source); from.debit(amount); log(Transaction.Type.WITHDRAWAL, amount, from, null); }
                case "transfer" -> {
                    if (Objects.equals(source, destination)) throw new IllegalArgumentException("Choose two different accounts for a transfer.");
                    Account from = account(source), to = account(destination);
                    from.debit(amount); to.credit(amount); log(Transaction.Type.TRANSFER, amount, from, to);
                }
                default -> throw new IllegalArgumentException("Unknown transaction type.");
            }
            repository.save(state);
        } catch (IOException | RuntimeException e) { state = backup; throw e; }
    }
    private void log(Transaction.Type type, BigDecimal amount, Account from, Account to) {
        state.transactions.add(new Transaction(state.nextTransaction++, type, amount, Instant.now(),
            from == null ? null : from.number(), to == null ? null : to.number(),
            from == null ? null : from.balance(), to == null ? null : to.balance()));
    }
    /** A snapshot allows rollback if validation or saving fails, including both legs of a transfer. */
    private State copy() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) { out.writeObject(state); }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (State) in.readObject();
        } catch (ClassNotFoundException e) { throw new IOException(e); }
    }
}
