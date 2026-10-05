package bank;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.regex.*;

/** Exercises the real HTTP server with isolated storage. */
public final class WebsiteTest {
    private static final CookieManager COOKIES = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private static final HttpClient CLIENT = HttpClient.newBuilder().cookieHandler(COOKIES).build();
    private static String base;
    private static Path dataFile;
    private static int checks;
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++; System.out.println("PASS: " + message);
    }
    private static HttpResponse<String> get(String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private static String fields(String html, String action) {
        String form = html.substring(html.indexOf("action='" + action + "'"));
        Matcher m = Pattern.compile("name='(csrf|submission)' value='([^']+)'").matcher(form);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < 2 && m.find(); i++) result.append(m.group(1)).append("=").append(m.group(2)).append("&");
        return result.toString();
    }
    private static HttpResponse<String> post(String path, String body) throws Exception {
        // Existing scenarios use valid identity details; rawPost covers missing/invalid credentials.
        if (path.equals("/open") || (path.equals("/admin") && body.contains("action=customer"))) body += "&phone=9876543210";
        if (path.equals("/transaction") && Files.exists(dataFile)) {
            BankSystem bank = new BankSystem(new FileBankRepository(dataFile));
            Customer c = bank.customers().get(0);
            body += "&fullName=" + URLEncoder.encode(c.name(), java.nio.charset.StandardCharsets.UTF_8) + "&card=" + c.cardNumber()
                + "&recipientName=" + URLEncoder.encode(c.name(), java.nio.charset.StandardCharsets.UTF_8) + "&recipientCard=" + c.cardNumber();
        }
        return rawPost(path, body);
    }
    private static HttpResponse<String> rawPost(String path, String body) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("bank-http-test-");
        dataFile = dir.resolve("bank.dat");
        int port;
        try (var socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
        base = "http://127.0.0.1:" + port;
        Process server = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"), "bank.BankApplication", "" + port, dir.resolve("bank.dat").toString()).redirectErrorStream(true).redirectOutput(dir.resolve("server.log").toFile()).start();
        try {
            boolean ready = false;
            for (int i = 0; i < 50; i++) { try { get("/"); ready = true; break; } catch (java.io.IOException e) { Thread.sleep(100); } }
            check(ready, "Website starts");
            check(get("/").statusCode() == 303 && get("/").headers().firstValue("Location").orElse("").equals("/login"), "Anonymous visitors must log in");
            check(rawPost("/admin", "action=account&id=A100001&minimum=0&status=Closed").statusCode() == 303, "Anonymous admin requests cannot mutate records");
            String login = get("/admin-login").body();
            check(rawPost("/admin-login", fields(login, "/admin-login") + "username=admin&password=wrong").statusCode() == 401, "Wrong admin password rejected");
            check(rawPost("/admin-login", fields(get("/admin-login").body(), "/admin-login") + "username=admin&password=admin123").statusCode() == 303, "Admin login succeeds");
            String html = get("/").body();
            check(html.contains("No accounts yet"), "Empty state is displayed");
            String body = fields(html, "/open") + "customer=&name=Test+Customer&email=test%40example.com&opening=1000&minimum=500";
            check(post("/open", body).statusCode() == 303, "Account form creates account and redirects");
            check(post("/open", body).statusCode() == 409, "Repeated submission is rejected");
            html = get("/").body();
            check(html.contains("A100001") && html.contains("Test Customer"), "Created account is visible");
            Customer issued = new BankSystem(new FileBankRepository(dataFile)).customers().get(0);
            check(issued.cardNumber().matches("[1-9][0-9]{11}") && issued.phone().equals("9876543210"), "Phone and random 12-digit card persist");
            check(html.contains("data-card='" + issued.cardNumber() + "'"), "Admin provides card copy control");
            check(rawPost("/transaction", fields(get("/").body(), "/transaction") + "type=deposit&source=A100001&amount=1").statusCode() == 400, "Missing name/card rejected");
            check(rawPost("/transaction", fields(get("/").body(), "/transaction") + "type=deposit&source=A100001&amount=1&fullName=Wrong&card=" + issued.cardNumber()).statusCode() == 400, "Mismatched name rejected");
            check(rawPost("/transaction", fields(get("/").body(), "/transaction") + "type=deposit&source=A100001&amount=1&fullName=Test+Customer&card=000000000000").statusCode() == 400, "Mismatched card rejected");
            check(rawPost("/open", fields(get("/").body(), "/open") + "customer=&name=New&email=new%40example.com&opening=0&minimum=0").statusCode() == 400, "New customer requires phone number");
            check(post("/open", fields(html, "/open") + "customer=C1&opening=500&minimum=100").statusCode() == 303, "Existing customer can open another account");
            check(post("/transaction", fields(get("/").body(), "/transaction") + "type=deposit&source=A100001&amount=250").statusCode() == 303, "Deposit form succeeds");
            check(post("/transaction", fields(get("/").body(), "/transaction") + "type=withdraw&source=A100001&amount=250").statusCode() == 303, "Withdrawal form succeeds");
            check(post("/transaction", fields(get("/").body(), "/transaction") + "type=transfer&source=A100001&destination=A100002&amount=500").statusCode() == 303, "Transfer form succeeds");
            var rejected = post("/transaction", fields(get("/").body(), "/transaction") + "type=withdraw&source=A100001&amount=0.01");
            check(rejected.statusCode() == 400 && rejected.body().contains("remaining balance"), "Minimum balance error is displayed");
            check(get("/?account=A100002").body().contains("TRANSFER IN"), "Receiving account shows incoming transfer");
            check(get("/?account=A100001").body().contains("TRANSFER OUT"), "Sending account shows outgoing transfer");
            check(post("/transaction", "type=deposit&source=A100001&amount=100").statusCode() == 403, "Missing anti-forgery token is rejected");
            check(get("/?account=missing").statusCode() == 400, "Unknown history account gives a handled error");
            check(get("/missing").statusCode() == 404, "Unknown route gives 404");
            check(get("/?message=%3Cscript%3Ebad%3C%2Fscript%3E").body().contains("&lt;script&gt;bad&lt;/script&gt;"), "User-controlled output is HTML escaped");
            check(new BankSystem(new FileBankRepository(dir.resolve("bank.dat"))).account("A100001").balance().toPlainString().equals("500.00"), "Website saves real balances to disk");
            check(post("/admin", fields(get("/").body(), "/admin") + "action=customer&id=C1&name=Updated+Customer&email=updated%40example.com").statusCode() == 303, "Admin edits customer through website");
            BankSystem adminBank = new BankSystem(new FileBankRepository(dir.resolve("bank.dat")));
            check(adminBank.account("A100001").holder().equals("Updated Customer") && adminBank.account("A100002").holder().equals("Updated Customer"), "Customer edit updates every linked account and survives reload");
            check(post("/admin", fields(get("/").body(), "/admin") + "action=account&id=A100001&minimum=0&status=Frozen").statusCode() == 303, "Admin freezes account");
            check(post("/transaction", fields(get("/").body(), "/transaction") + "type=deposit&source=A100001&amount=1").statusCode() == 400, "Frozen account rejects deposits on server");
            check(post("/transaction", fields(get("/").body(), "/transaction") + "type=transfer&source=A100002&destination=A100001&amount=1").statusCode() == 400, "Frozen recipient rejects transfer");
            adminBank = new BankSystem(new FileBankRepository(dir.resolve("bank.dat")));
            check(adminBank.account("A100002").balance().toPlainString().equals("1000.00"), "Rejected transfer to frozen recipient rolls back source debit");
            check(post("/admin", fields(get("/").body(), "/admin") + "action=account&id=A100001&minimum=501&status=Active").statusCode() == 400, "Minimum above balance rejected");
            check(post("/admin", fields(get("/").body(), "/admin") + "action=account&id=A100001&minimum=0&status=Closed").statusCode() == 400, "Nonzero balance prevents closure");
            check(post("/admin", fields(get("/").body(), "/admin") + "action=account&id=A100001&minimum=0&status=Active").statusCode() == 303, "Account can be reactivated");
            check(post("/transaction", fields(get("/").body(), "/transaction") + "type=withdraw&source=A100001&amount=500").statusCode() == 303, "Reactivated account can withdraw remaining balance");
            check(post("/admin", fields(get("/").body(), "/admin") + "action=account&id=A100001&minimum=0&status=Closed").statusCode() == 303, "Zero balance account can close");
            check(post("/admin", fields(get("/").body(), "/admin") + "action=account&id=A100001&minimum=0&status=Active").statusCode() == 400, "Closed account cannot reopen");
            check(post("/transaction", fields(get("/").body(), "/transaction") + "type=deposit&source=A100001&amount=1").statusCode() == 400, "Closed account rejects transactions");
            check(get("/?account=A100001").body().contains("WITHDRAWAL"), "Closed account retains financial history");
            check(post("/admin", "action=customer&id=C1&name=Bad&email=bad%40example.com").statusCode() == 403, "Admin requires anti-forgery token");
            check(post("/admin", fields(get("/").body(), "/admin") + "action=customer&id=C1&name=&email=bad").statusCode() == 400, "Invalid customer edit rejected");
            adminBank = new BankSystem(new FileBankRepository(dir.resolve("bank.dat")));
            check(adminBank.adminLog().size() == 4 && adminBank.account("A100001").status().equals("Closed"), "Successful administrative changes and statuses persist; rejected changes are not logged");
            check(post("/open", fields(get("/").body(), "/open") + "customer=&name=Other+Person&email=other%40example.com&opening=200&minimum=0").statusCode() == 303, "Admin creates separate customer for authorization checks");
            String adminForm = fields(get("/").body(), "/admin");
            check(rawPost("/logout", fields(get("/").body(), "/logout")).statusCode() == 303 && get("/").statusCode() == 303, "Logout invalidates authenticated session");
            Customer user = new BankSystem(new FileBankRepository(dataFile)).customers().get(0);
            check(rawPost("/login", fields(get("/login").body(), "/login") + "fullName=Wrong&card=" + user.cardNumber()).statusCode() == 401, "Customer name must match card");
            check(rawPost("/login", fields(get("/login").body(), "/login") + "fullName=Updated+Customer&card=" + user.cardNumber()).statusCode() == 303, "Customer login succeeds");
            html = get("/").body();
            check(html.contains("A100002") && !html.contains("Other Person") && !html.contains("A100003</") && !html.contains("data-card=") && !html.contains("Customer directory"), "Customer response excludes admin records and other customer data");
            check(get("/?account=A100003").statusCode() == 403, "Customer cannot read another customer's history");
            check(rawPost("/admin", adminForm + "action=account&id=A100003&minimum=0&status=Closed").statusCode() == 403, "Customer cannot use administrator endpoint or old admin form");
            check(rawPost("/open", "customer=C1&opening=0&minimum=0").statusCode() == 403, "Customer cannot create or manage accounts through admin route");
            check(rawPost("/transaction", fields(get("/").body(), "/transaction") + "type=withdraw&source=A100003&amount=1").statusCode() == 403, "Server rejects another customer's source account");
            check(rawPost("/transaction", fields(get("/").body(), "/transaction") + "type=deposit&source=A100002&amount=1&fullName=Updated+Customer&card=" + user.cardNumber()).statusCode() == 303, "Customer can transact on own active account");
            check(rawPost("/logout", "csrf=wrong").statusCode() == 403 && get("/").statusCode() == 200, "Logout requires valid session form");
            String oldForm = fields(get("/").body(), "/transaction");
            rawPost("/logout", fields(get("/").body(), "/logout"));
            check(rawPost("/transaction", oldForm + "type=deposit&source=A100002&amount=1").statusCode() == 303, "Logged-out session cannot reuse transaction form");
            System.out.println("All " + checks + " website checks passed.");
        } finally {
            server.destroy(); server.waitFor();
            try (var files = Files.list(dir)) { for (Path p : files.toList()) Files.deleteIfExists(p); }
            Files.deleteIfExists(dir);
        }
    }
}
