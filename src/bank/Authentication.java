package bank;

import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.PBEKeySpec;

/** Server-held sessions; credentials and session identifiers never appear in URLs. */
final class Authentication {
    static final class Session {
        final String id = randomToken(), csrf = randomToken();
        final Set<String> submissions = new HashSet<>();
        final String customerId;
        final boolean admin;
        final long created = System.currentTimeMillis();
        long touched = created;
        Session(String customerId, boolean admin) { this.customerId = customerId; this.admin = admin; }
        boolean authenticated() { return admin || customerId != null; }
    }
    private final Map<String, Session> sessions = new HashMap<>();
    private final Map<String, long[]> failures = new HashMap<>();
    static String randomToken() { byte[] b = new byte[32]; new SecureRandom().nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    synchronized Session find(String id) {
        long now = System.currentTimeMillis();
        sessions.values().removeIf(s -> now - s.touched > 30 * 60_000L || now - s.created > 8 * 60 * 60_000L);
        Session session = sessions.get(id); if (session != null) session.touched = now; return session;
    }
    synchronized Session create(String customerId, boolean admin) {
        find("");
        if (sessions.size() >= 10000) throw new IllegalArgumentException("Too many sessions. Please try again later.");
        Session session = new Session(customerId, admin); sessions.put(session.id, session); return session;
    }
    synchronized void remove(String id) { sessions.remove(id); }
    synchronized boolean blocked(String address) {
        long now = System.currentTimeMillis(); failures.values().removeIf(f -> now - f[1] >= 60_000);
        long[] f = failures.get(address); return f != null && f[0] >= 5;
    }
    synchronized void failure(String address) {
        long[] f = failures.computeIfAbsent(address, k -> new long[]{0, System.currentTimeMillis()}); f[0]++;
    }
    synchronized void success(String address) { failures.remove(address); }
    static boolean adminPassword(String username, String password) {
        if (password == null || password.length() > 128) return false;
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), Base64.getDecoder().decode("gn8Hf2k1idyseK9YyoSBHw=="), 210000, 256);
        try {
            byte[] hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return MessageDigest.isEqual(hash, Base64.getDecoder().decode("sydST4j/GU+O786JKbp44QdR1VmgtvKWRP3KxqZR2JY=")) && "admin".equals(username);
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
        finally { spec.clearPassword(); }
    }
}
