package bank;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Security regressions use isolated keys and never the live bank. */
public final class EncryptionTest {
    private static int checks;
    private static void check(boolean ok, String description) { if (!ok) throw new AssertionError(description); checks++; System.out.println("PASS: " + description); }
    private static void fails(FileBankRepository repository, String description) throws Exception {
        try { repository.load(); throw new AssertionError(description); } catch (IOException expected) { check(true, description); }
    }
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("bank-encryption-");
        String previous = System.getProperty("bank.keyDirectory"); System.setProperty("bank.keyDirectory", dir.toString());
        try {
            Path file = dir.resolve("bank.dat"); FileBankRepository repository = new FileBankRepository(file);
            BankSystem bank = new BankSystem(repository);
            String account = bank.openAccount("", "Secret Person", "private@example.com", "100", "0", "9876543210");
            byte[] first = Files.readAllBytes(file);
            String raw = new String(first, java.nio.charset.StandardCharsets.ISO_8859_1);
            check(EncryptedStorage.encrypted(first) && !raw.contains("Secret Person") && !raw.contains("private@example.com") && !raw.contains(bank.customers().get(0).cardNumber()), "Sensitive fields are absent from encrypted disk bytes");
            check(new BankSystem(repository).account(account).balance().toPlainString().equals("100.00"), "Encrypted data decrypts after restart");
            repository.save(repository.load()); byte[] second = Files.readAllBytes(file);
            check(!Arrays.equals(first, second), "Fresh nonce produces different ciphertext for identical state");
            byte[] tampered = second.clone(); tampered[tampered.length - 1] ^= 1; Files.write(file, tampered);
            fails(repository, "Tampered ciphertext is rejected");
            check(Arrays.equals(tampered, Files.readAllBytes(file)), "Failed decryption never overwrites the bank");
            Files.write(file, second);
            Path key;
            try (var paths = Files.list(dir)) { key = paths.filter(p -> p.toString().endsWith(".key")).findFirst().orElseThrow(); }
            byte[] secret = Files.readAllBytes(key); byte[] wrong = secret.clone(); wrong[0] ^= 1; Files.write(key, wrong);
            fails(repository, "Wrong encryption key is rejected");
            Files.delete(key); fails(repository, "Missing encryption key is not regenerated for encrypted data");
            Files.write(key, secret);
            Path legacy = dir.resolve("legacy.dat");
            try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(legacy))) { out.writeObject(repository.load()); }
            BankSystem migrated = new BankSystem(new FileBankRepository(legacy));
            check(EncryptedStorage.encrypted(Files.readAllBytes(legacy)) && migrated.account(account).balance().toPlainString().equals("100.00"), "Legacy migration preserves records and replaces plaintext atomically");
            System.out.println("All " + checks + " encryption checks passed.");
        } finally {
            if (previous == null) System.clearProperty("bank.keyDirectory"); else System.setProperty("bank.keyDirectory", previous);
            try (var paths = Files.list(dir)) { for (Path p : paths.toList()) Files.delete(p); } Files.delete(dir);
        }
    }
}
