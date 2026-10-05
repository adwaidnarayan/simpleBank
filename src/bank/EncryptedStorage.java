package bank;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** AES-256-GCM envelope with a random nonce and authenticated format header. */
final class EncryptedStorage {
    private static final byte[] MAGIC = {'S','B','A','N','K',1};
    private final Path keyFile;
    EncryptedStorage(Path file) {
        try {
            String id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.toAbsolutePath().normalize().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            Path keys = Path.of(System.getProperty("bank.keyDirectory", Path.of(System.getProperty("user.home"), ".simple-bank-keys").toString()));
            keyFile = keys.resolve(id + ".key");
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    private byte[] key(boolean create) throws IOException {
        if (!Files.exists(keyFile)) {
            if (!create) throw new IOException("Encryption key missing. Restore the original key; data has not been changed.");
            Files.createDirectories(keyFile.getParent()); restrict(keyFile.getParent());
            byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
            Path temp = Files.createTempFile(keyFile.getParent(), "key-", ".tmp");
            try {
                restrict(temp); Files.write(temp, bytes);
                Files.move(temp, keyFile, StandardCopyOption.ATOMIC_MOVE);
            } finally { Files.deleteIfExists(temp); Arrays.fill(bytes, (byte)0); }
        }
        restrict(keyFile);
        byte[] bytes = Files.readAllBytes(keyFile);
        if (bytes.length != 32) throw new IOException("Invalid encryption key. Restore the original key.");
        return bytes;
    }
    private static void restrict(Path path) throws IOException {
        if (Files.getFileAttributeView(path, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(Files.isDirectory(path) ? "rwx------" : "rw-------")); return;
        }
        var acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (acl == null) throw new IOException("Cannot restrict key permissions on this filesystem.");
        acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(path)).setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
    }
    static boolean encrypted(byte[] bytes) { return bytes.length >= MAGIC.length && Arrays.equals(Arrays.copyOf(bytes, MAGIC.length), MAGIC); }
    byte[] transform(byte[] input, boolean encrypt, boolean allowNewKey) throws IOException {
        byte[] secret = key(allowNewKey);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); byte[] iv = new byte[12];
            if (encrypt) new SecureRandom().nextBytes(iv);
            else {
                if (!encrypted(input) || input.length < MAGIC.length + 28) throw new IOException("Invalid encrypted bank file.");
                System.arraycopy(input, MAGIC.length, iv, 0, 12);
            }
            cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(secret, "AES"), new GCMParameterSpec(128, iv)); cipher.updateAAD(MAGIC);
            if (!encrypt) return cipher.doFinal(input, MAGIC.length + 12, input.length - MAGIC.length - 12);
            ByteArrayOutputStream out = new ByteArrayOutputStream(); out.write(MAGIC); out.write(iv); out.write(cipher.doFinal(input)); return out.toByteArray();
        } catch (GeneralSecurityException e) { throw new IOException("Bank data authentication failed: wrong key or modified file.", e); }
        finally { Arrays.fill(secret, (byte)0); }
    }
}
