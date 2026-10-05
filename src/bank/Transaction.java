package bank;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;

/** One immutable log entry per successful operation; transfers reference both accounts. */
public record Transaction(long id, Type type, BigDecimal amount, Instant date,
                          String source, String destination, BigDecimal sourceBalance,
                          BigDecimal destinationBalance) implements Serializable {
    public enum Type { DEPOSIT, WITHDRAWAL, TRANSFER }
}
