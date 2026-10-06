package bank;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

public final class SqliteTest {
    private static int checks;
    private static void check(boolean ok, String text) { if (!ok) throw new AssertionError(text); checks++; System.out.println("PASS: " + text); }
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("bank-sqlite-");
        System.setProperty("bank.keyDirectory", dir.toString());
        Path db = dir.resolve("bank.db"), legacy = dir.resolve("bank.dat");
        try {
            BankSystem old = new BankSystem(new FileBankRepository(legacy));
            String a = old.openAccount("", "SQLite Customer", "db@example.com", "1000", "100", "9876543210");
            BankSystem bank = new BankSystem(new SqliteBankRepository(db, legacy));
            check(bank.account(a).balance().toPlainString().equals("1000.00"), "Legacy bank imported into SQLite");
            check(Files.exists(legacy), "Original encrypted bank retained for recovery");
            String b = bank.openAccount("C1", "", "", "100", "0", "");
            bank.transact("transfer", a, b, "200");
            BankSystem restart = new BankSystem(new SqliteBankRepository(db, legacy));
            check(restart.account(a).balance().toPlainString().equals("800.00") && restart.account(b).balance().toPlainString().equals("300.00"), "SQLite persists both sides of transfer across restart");
            check(restart.customers().get(0).cardNumber().equals(old.customers().get(0).cardNumber()) && restart.history(a).size() == 2, "Cards and history survive migration and restart");
            check(!new String(Files.readAllBytes(db), java.nio.charset.StandardCharsets.ISO_8859_1).contains("SQLite Customer"), "SQLite file contains encrypted personal records");
            bank.transact("deposit", a, "", "1");
            try { restart.transact("deposit", a, "", "100"); throw new AssertionError("Stale writer accepted"); } catch (IOException expected) { check(true, "Stale writer rejected to prevent lost updates"); }
            check(new BankSystem(new SqliteBankRepository(db)).account(a).balance().toPlainString().equals("801.00"), "Rejected stale write preserves committed balance");
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement s = c.createStatement()) {
                try (ResultSet result = s.executeQuery("PRAGMA integrity_check")) { check(result.next() && "ok".equals(result.getString(1)), "SQLite integrity check passes"); }
                s.executeUpdate("UPDATE bank_state SET payload=x'00010203' WHERE id=1");
            }
            try { new BankSystem(new SqliteBankRepository(db)); throw new AssertionError("Tampering accepted"); } catch (IOException expected) { check(true, "Tampered encrypted SQLite record rejected"); }
            System.out.println("All " + checks + " SQLite checks passed.");
        } finally {
            System.clearProperty("bank.keyDirectory");
            try (var files = Files.list(dir)) { for (Path p : files.toList()) Files.deleteIfExists(p); } Files.delete(dir);
        }
    }
}
