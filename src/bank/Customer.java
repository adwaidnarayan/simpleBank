package bank;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** A customer may own several accounts. */
public final class Customer implements Serializable {
    private static final long serialVersionUID = 1L;
    private final String id;
    private String name, email;
    private String phone, cardNumber;
    private final List<String> accountNumbers = new ArrayList<>();
    public Customer(String id, String name, String email) { this.id = id; this.name = name; this.email = email; }
    public String id() { return id; }
    public String name() { return name; }
    public String email() { return email; }
    public String phone() { return phone == null ? "" : phone; }
    public String cardNumber() { return cardNumber; }
    void phone(String phone) { this.phone = phone; }
    void issueCard(String number) { if (cardNumber == null) cardNumber = number; }
    public List<String> accountNumbers() { return List.copyOf(accountNumbers); }
    void addAccount(String number) { accountNumbers.add(number); }
    void update(String name, String email) { this.name = name; this.email = email; }
}
