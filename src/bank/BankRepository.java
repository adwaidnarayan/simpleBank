package bank;

import java.io.IOException;

/** Storage abstraction: banking logic does not depend on a particular storage mechanism. */
public interface BankRepository {
    BankSystem.State load() throws IOException;
    void save(BankSystem.State state) throws IOException;
}
