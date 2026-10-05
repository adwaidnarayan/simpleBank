package bank;

import java.io.*;
import java.nio.file.*;

/** Encrypts the full state before any disk write; atomically migrates validated legacy data. */
public final class FileBankRepository implements BankRepository {
    private final Path file;
    private EncryptedStorage encryption() { return new EncryptedStorage(file); }
    public FileBankRepository(Path file) { this.file = file.toAbsolutePath(); }
    public BankSystem.State load() throws IOException {
        if (!Files.exists(file)) return new BankSystem.State();
        if (Files.size(file) > 100000000) throw new IOException("Bank data exceeds size limit.");
        byte[] stored = Files.readAllBytes(file);
        boolean legacy = stored.length >= 2 && stored[0] == (byte)0xac && stored[1] == (byte)0xed;
        byte[] plain = legacy ? stored : encryption().transform(stored, false, false);
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(plain))) {
            in.setObjectInputFilter(ObjectInputFilter.Config.createFilter("maxdepth=30;maxrefs=1000000;maxbytes=100000000;bank.*;java.base/*;!*"));
            BankSystem.State state = (BankSystem.State) in.readObject();
            if (legacy) save(state);
            return state;
        } catch (ClassNotFoundException | ClassCastException e) { throw new IOException("Cannot read bank data", e); }
        finally { java.util.Arrays.fill(plain, (byte)0); }
    }
    public void save(BankSystem.State state) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(buffer)) { out.writeObject(state); }
        byte[] plain = buffer.toByteArray(), ciphertext;
        boolean newKey = !Files.exists(file) || !EncryptedStorage.encrypted(Files.readAllBytes(file));
        try { ciphertext = encryption().transform(plain, true, newKey); }
        finally { java.util.Arrays.fill(plain, (byte)0); }
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), "bank-", ".tmp");
        try {
            Files.write(temp, ciphertext);
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    public static void main(String[] paths) throws IOException {
        for (String path : paths) new FileBankRepository(Path.of(path)).load();
    }
}
