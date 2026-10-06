package bank;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.Arrays;

/** SQLite transaction stores one authenticated, encrypted aggregate, preserving banking invariants. */
public final class SqliteBankRepository implements BankRepository {
    private final Path file, legacy;
    private long revision = -1;
    public SqliteBankRepository(Path file) { this(file, null); }
    public SqliteBankRepository(Path file, Path legacy) { this.file = file.toAbsolutePath().normalize(); this.legacy = legacy; }
    private Connection connect() throws SQLException, IOException {
        Files.createDirectories(file.getParent());
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("CREATE TABLE IF NOT EXISTS bank_state (id INTEGER PRIMARY KEY CHECK(id=1), revision INTEGER NOT NULL, payload BLOB NOT NULL, updated_at TEXT NOT NULL)");
        } catch (SQLException e) { connection.close(); throw e; }
        return connection;
    }
    public synchronized BankSystem.State load() throws IOException {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT revision,payload FROM bank_state WHERE id=1")) {
            if (r.next()) {
                revision = r.getLong(1);
                byte[] plain = new EncryptedStorage(file).transform(r.getBytes(2), false, false);
                try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(plain))) {
                    in.setObjectInputFilter(ObjectInputFilter.Config.createFilter("maxdepth=30;maxrefs=1000000;maxbytes=100000000;bank.*;java.base/*;!*"));
                    return (BankSystem.State)in.readObject();
                } catch (ClassNotFoundException | ClassCastException e) { throw new IOException("Cannot decode database records", e); }
                finally { Arrays.fill(plain, (byte)0); }
            }
        } catch (SQLException e) { throw new IOException("Cannot load SQLite database", e); }
        revision = -1;
        BankSystem.State initial = legacy != null && Files.exists(legacy) ? new FileBankRepository(legacy).load() : new BankSystem.State();
        save(initial); // Import once; keep original encrypted file for recovery.
        return initial;
    }
    public synchronized void save(BankSystem.State state) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(buffer)) { out.writeObject(state); }
        byte[] plain = buffer.toByteArray(), encrypted;
        try { encrypted = new EncryptedStorage(file).transform(plain, true, revision == -1); }
        finally { Arrays.fill(plain, (byte)0); }
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                String sql = revision == -1
                    ? "INSERT INTO bank_state(id,revision,payload,updated_at) VALUES(1,0,?,datetime('now'))"
                    : "UPDATE bank_state SET revision=revision+1,payload=?,updated_at=datetime('now') WHERE id=1 AND revision=?";
                try (PreparedStatement statement = c.prepareStatement(sql)) {
                    statement.setBytes(1, encrypted);
                    if (revision != -1) statement.setLong(2, revision);
                    if (statement.executeUpdate() != 1) throw new SQLException("Database changed in another process. Restart before writing.");
                }
                c.commit(); revision++;
            } catch (SQLException e) { c.rollback(); throw e; }
        } catch (SQLException e) { throw new IOException("Cannot commit SQLite transaction; no changes saved", e); }
    }
}
