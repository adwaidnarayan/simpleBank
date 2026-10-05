package bank;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Small server-rendered website. All financial validation runs in BankSystem. */
public final class BankApplication {
    private final BankSystem bank;
    private final Authentication authentication = new Authentication();
    private final ThreadLocal<Authentication.Session> current = new ThreadLocal<>();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm:ss").withZone(ZoneId.of("Asia/Kolkata"));
    private BankApplication(BankSystem bank) { this.bank = bank; }
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args.length > 0 ? args[0] : System.getenv().getOrDefault("PORT", "8080"));
        String host = System.getenv().getOrDefault("BANK_HOST", "127.0.0.1");
        Path data = Path.of(args.length > 1 ? args[1] : System.getenv().getOrDefault("BANK_DATA_FILE", "data/bank.dat"));
        BankApplication app = new BankApplication(new BankSystem(new FileBankRepository(data)));
        String tlsStore = System.getProperty("bank.tls.keystore");
        HttpServer server;
        if (tlsStore != null) {
            String password = System.getenv("BANK_TLS_PASSWORD");
            if (password == null || password.isEmpty()) throw new IllegalStateException("Set BANK_TLS_PASSWORD for the TLS keystore.");
            java.security.KeyStore keys = java.security.KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(Path.of(tlsStore))) { keys.load(input, password.toCharArray()); }
            var km = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
            km.init(keys, password.toCharArray());
            var ssl = javax.net.ssl.SSLContext.getInstance("TLS"); ssl.init(km.getKeyManagers(), null, null);
            HttpsServer https = HttpsServer.create(new InetSocketAddress(host, port), 0);
            https.setHttpsConfigurator(new HttpsConfigurator(ssl) {
                @Override public void configure(HttpsParameters parameters) {
                    var settings = getSSLContext().getDefaultSSLParameters(); settings.setProtocols(new String[]{"TLSv1.3", "TLSv1.2"}); parameters.setSSLParameters(settings);
                }
            });
            server = https;
        } else server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/health", e -> {
            if (!e.getRequestURI().getPath().equals("/health")) { e.sendResponseHeaders(404, -1); e.close(); return; }
            byte[] status = "ok".getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().set("Content-Type", "text/plain");
            e.sendResponseHeaders(200, status.length); e.getResponseBody().write(status); e.close();
        });
        server.createContext("/", app::handle);
        server.start();
        System.out.println("Simple Banking System is running at " + (tlsStore == null ? "http" : "https") + "://" + host + ":" + port);
        System.out.println("Data: " + data.toAbsolutePath());
    }
    private void handle(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        try {
            String path = exchange.getRequestURI().getPath();
            String cookie = exchange.getRequestHeaders().getFirst("Cookie"), sessionId = "";
            if (cookie != null) for (String part : cookie.split(";")) if (part.trim().startsWith("BANK_SESSION=")) sessionId = part.trim().substring(13);
            Authentication.Session session = authentication.find(sessionId);
            if (session == null) { session = authentication.create(null, false); setSessionCookie(exchange, session); }
            current.set(session);
            if (path.equals("/login") || path.equals("/admin-login")) {
                boolean admin = path.equals("/admin-login");
                if (exchange.getRequestMethod().equals("GET")) { send(exchange, 200, loginPage(admin, null)); return; }
                if (!exchange.getRequestMethod().equals("POST")) { send(exchange, 405, loginPage(admin, "Method not allowed.")); return; }
                byte[] body = exchange.getRequestBody().readNBytes(16385);
                if (body.length > 16384) { send(exchange, 413, loginPage(admin, "Form too large.")); return; }
                Map<String,String> form = parse(new String(body, StandardCharsets.UTF_8));
                if (!session.csrf.equals(form.get("csrf")) || !session.submissions.remove(form.get("submission"))) { send(exchange, 403, loginPage(admin, "Login form expired. Please try again.")); return; }
                String address = exchange.getRemoteAddress().getAddress().getHostAddress();
                if (authentication.blocked(address)) { exchange.getResponseHeaders().set("Retry-After", "60"); send(exchange, 429, loginPage(admin, "Too many failed attempts. Wait one minute and try again.")); return; }
                String customerId = null;
                boolean valid;
                if (admin) valid = Authentication.adminPassword(form.get("username"), form.get("password"));
                else {
                    for (Customer c : bank.customers()) if (c.name().equalsIgnoreCase(form.getOrDefault("fullName", "").trim()) && c.cardNumber().equals(form.get("card"))) customerId = c.id();
                    valid = customerId != null;
                }
                if (!valid) { authentication.failure(address); send(exchange, 401, loginPage(admin, "The login details do not match. Please try again.")); return; }
                authentication.success(address); authentication.remove(session.id);
                session = authentication.create(customerId, admin); current.set(session); setSessionCookie(exchange, session);
                redirect(exchange, admin ? "/#admin" : "/#dashboard"); return;
            }
            if (!session.authenticated()) { redirect(exchange, "/login"); return; }
            if (path.equals("/logout")) {
                if (!exchange.getRequestMethod().equals("POST")) { send(exchange, 405, page("Use the sign out button.", true, null)); return; }
                Map<String,String> form = parse(new String(exchange.getRequestBody().readNBytes(16384), StandardCharsets.UTF_8));
                if (!session.csrf.equals(form.get("csrf")) || !session.submissions.remove(form.get("submission"))) { send(exchange, 403, page("Form expired.", true, null)); return; }
                authentication.remove(session.id);
                exchange.getResponseHeaders().set("Set-Cookie", "BANK_SESSION=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict" + ((exchange instanceof HttpsExchange || Boolean.parseBoolean(System.getenv("BANK_SECURE_COOKIES"))) ? "; Secure" : ""));
                redirect(exchange, "/login"); return;
            }
            if (!session.admin && (path.equals("/admin") || path.equals("/open"))) { send(exchange, 403, page("Administrator access is required.", true, null)); return; }
            if (exchange.getRequestMethod().equals("POST")) {
                if (!path.equals("/open") && !path.equals("/transaction") && !path.equals("/admin")) { send(exchange, 404, page("Page not found", true, null)); return; }
                byte[] body = exchange.getRequestBody().readNBytes(16385);
                if (body.length > 16384) { send(exchange, 413, page("Form is too large.", true, null)); return; }
                Map<String, String> form = parse(new String(body, StandardCharsets.UTF_8));
                if (!session.csrf.equals(form.get("csrf"))) { send(exchange, 403, page("This form has expired. Please try again.", true, null)); return; }
                String nonce = form.get("submission");
                if (nonce == null || !session.submissions.remove(nonce)) { send(exchange, 409, page("This form was already submitted or has expired. Check the account history before submitting again.", true, null)); return; }
                String message;
                if (path.equals("/admin")) {
                    if ("customer".equals(form.get("action"))) bank.updateCustomer(form.get("id"), form.get("name"), form.get("email"), form.get("phone"));
                    else if ("account".equals(form.get("action"))) bank.updateAccount(form.get("id"), form.get("minimum"), form.get("status"));
                    else throw new IllegalArgumentException("Unknown administration action.");
                    message = "Administrative changes saved successfully.";
                } else if (path.equals("/open")) {
                    String number = bank.openAccount(form.get("customer"), form.get("name"), form.get("email"), form.get("opening"), form.get("minimum"), form.get("phone"));
                    message = "Account " + number + " opened successfully.";
                } else {
                    if (!session.admin && !bank.account(form.get("source")).customerId().equals(session.customerId)) { send(exchange, 403, page("You can only transact from your own accounts.", true, null)); return; }
                    bank.transactWithCard(form.getOrDefault("type", ""), form.get("source"), form.get("destination"), form.get("amount"), form.get("fullName"), form.get("card"), form.get("recipientName"), form.get("recipientCard"));
                    message = "Transaction completed successfully. Balances and transaction history have been updated.";
                }
                exchange.getResponseHeaders().set("Location", "/?message=" + URLEncoder.encode(message, StandardCharsets.UTF_8) + (path.equals("/admin") ? "#admin" : path.equals("/open") ? "#accounts" : "#transactions"));
                exchange.sendResponseHeaders(303, -1);
            } else if (exchange.getRequestMethod().equals("GET") && path.equals("/")) {
                Map<String, String> query = parse(exchange.getRequestURI().getRawQuery());
                if (!session.admin && query.containsKey("account") && !bank.account(query.get("account")).customerId().equals(session.customerId)) { send(exchange, 403, page("You can only view your own account history.", true, null)); return; }
                send(exchange, 200, page(query.get("message"), false, query.get("account")));
            } else { send(exchange, 404, page("Page not found.", true, null)); }
        } catch (IllegalArgumentException e) { send(exchange, 400, page(e.getMessage(), true, null)); }
        catch (Exception e) {
            e.printStackTrace(); send(exchange, 500, page("The operation could not be saved. No balance changes were committed. Please try again.", true, null));
        } finally { current.remove(); exchange.close(); }
    }
    private String formStart(String action) {
        var session = current.get();
        var submissions = session.submissions;
        if (submissions.size() > 10000) submissions.clear();
        String nonce = UUID.randomUUID().toString(); submissions.add(nonce);
        return "<form method='post' action='" + action + "'><input type='hidden' name='csrf' value='" + session.csrf + "'><input type='hidden' name='submission' value='" + nonce + "'>";
    }
    private String page(String message, boolean error, String selected) {
        if (current.get() == null || !current.get().authenticated()) return loginPage(false, message);
        if (!current.get().admin) return customerPage(message, error, selected);
        List<Account> accounts = bank.accounts();
        StringBuilder h = new StringBuilder("<!doctype html><html lang='en'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>Simple Bank | Banking portal</title><style>" + CSS + NEW_CSS + "</style></head><body><header><div class='wrap brand'><a class='brand-link' href='#dashboard'><span class='mark'>SB<span class='brand-dot'>.</span></span><div><strong>simple<span class='brand-light'>bank</span></strong><small>EVERYDAY BANKING</small></div></a><span class='local'><span class='status-dot'></span> Local banking portal</span><span class='operator'>BO</span><div class='operator-label'><strong>Bank operator</strong><small>Account management</small></div></div></header><div class='nav-shell'><nav class='wrap tabs' aria-label='Banking navigation'><a href='#dashboard' data-tab='dashboard'>Overview</a><a href='#accounts' data-tab='accounts'>Accounts</a><a href='#open-account' data-tab='open-account'>Open account</a><a href='#transactions' data-tab='transactions'>Transactions</a><a href='#history' data-tab='history'>History</a><a href='#admin' data-tab='admin'>Admin</a></nav></div><main class='wrap'><div class='title'><div><p class='eyebrow'>YOUR BANKING WORKSPACE</p><h1 id='page-title'>A clear view of your bank.</h1><p id='page-description'>Your accounts, balances, and everyday banking. All in one place.</p></div><a class='button' href='#open-account'>+ Open account</a></div>");
        if (message != null) h.append("<div role='alert' class='notice ").append(error ? "error" : "success").append("'>").append(esc(message)).append("</div>");
        java.math.BigDecimal total = accounts.stream().map(Account::balance).reduce(new java.math.BigDecimal("0.00"), java.math.BigDecimal::add);
        h.append("<div id='dashboard' class='tab-panel'><div class='dashboard-grid'><div class='balance-card'><div class='balance-top'><span>ACCOUNT SUMMARY</span><span class='card-symbol'>◎</span></div><p>Total funds across accounts</p><div class='balance-amount'><span>INR</span> ").append(total).append("</div><div class='balance-bottom'><span>Simple Bank<small>Every balance, accounted for.</small></span><a href='#accounts'>View accounts ↗</a></div></div><div class='stats'><div><small>CUSTOMERS</small><strong>").append(bank.customers().size()).append("</strong><p>Registered with your bank</p></div><div><small>ACTIVE ACCOUNTS</small><strong>").append(accounts.stream().filter(a -> a.status().equals("Active")).count()).append("</strong><p>Ready for everyday banking</p></div></div></div><section class='quick-section'><div class='section-title'><h2>Everyday banking</h2><span>What would you like to do?</span></div><div class='quick-actions'><a href='#open-account'><span class='quick-icon'>＋</span><strong>Open an account</strong><small>Start a new banking account</small><b>↗</b></a><a href='#transactions' data-operation='deposit'><span class='quick-icon'>↓</span><strong>Deposit funds</strong><small>Add money to an account</small><b>↗</b></a><a href='#transactions' data-operation='transfer'><span class='quick-icon'>⇄</span><strong>Transfer money</strong><small>Move funds between accounts</small><b>↗</b></a><a href='#history'><span class='quick-icon'>≡</span><strong>View activity</strong><small>Track account transactions</small><b>↗</b></a></div></section><section><div class='section-title'><h2>Account snapshot</h2><a href='#accounts'>View all accounts →</a></div><div class='account-snapshot'>");
        if (accounts.isEmpty()) h.append("<div class='empty'><h3>Your banking starts here</h3><p>Open an account to see your balances and activity.</p><a class='button secondary' href='#open-account'>Create your first account</a></div>");
        for (Account a : accounts.stream().limit(3).toList()) h.append("<a class='snapshot-row' href='/?account=").append(a.number()).append("#history'><span class='account-avatar'>SB</span><span><strong>").append(esc(a.holder())).append("</strong><small>").append(a.number()).append(" · ").append(a.customerId()).append("</small></span><span class='snapshot-balance'><strong>INR ").append(a.balance()).append("</strong><small>View activity →</small></span></a>");
        h.append("</div></section><div class='info-strip'><span class='info-icon'>i</span><div><strong>Minimum balance, always protected.</strong><p>Withdrawals and transfers are checked before they are processed.</p></div><a href='#transactions'>Make a transaction →</a></div></div><section id='accounts' class='tab-panel'><div class='section-title'><h2>All accounts</h2><span>All amounts in INR</span></div>");
        if (accounts.isEmpty()) h.append("<div class='empty'><h3>No accounts yet</h3><p>Open your first account using the form below.</p></div>");
        else {
            h.append("<div class='scroll'><table><thead><tr><th>Account number</th><th>Account holder</th><th>Customer ID</th><th>Status</th><th>Balance</th><th>Minimum balance</th><th>History</th></tr></thead><tbody>");
            for (Account a : accounts) h.append("<tr><td><strong>").append(a.number()).append("</strong></td><td>").append(esc(a.holder())).append("</td><td>").append(a.customerId()).append("</td><td>").append(a.status()).append("</td><td>").append(a.balance()).append("</td><td>").append(a.minimumBalance()).append("</td><td><a href='/?account=").append(a.number()).append("#history'>View history</a></td></tr>");
            h.append("</tbody></table></div>");
        }
        h.append("</section><section id='open-account' class='tab-panel form-panel'><div class='form-intro'><span class='quick-icon'>＋</span><p class='eyebrow'>A NEW BEGINNING</p><h2>Room for your next account.</h2><p>Create an account for a new customer, or keep an existing customer's accounts together.</p><div class='form-note'><strong>Before you begin</strong><p>Have the customer's name and email ready. Choose an opening deposit that meets the account's minimum balance.</p></div></div><div class='form-body'><h2>Account details</h2><p class='hint'>Complete the details below to open an account.</p>").append(formStart("/open"));
        h.append("<label>Customer<select name='customer' id='customer'><option value=''>New customer</option>");
        for (Customer c : bank.customers()) h.append("<option value='").append(c.id()).append("'>").append(esc(c.id() + " — " + c.name() + " (" + c.email() + ")")).append("</option>");
        h.append("</select></label><div id='new-customer'><label>Full name<input name='name' maxlength='100' autocomplete='name' required></label><label>Email address<input name='email' type='email' maxlength='254' autocomplete='email' required></label><label>Phone number<input name='phone' type='tel' autocomplete='tel' pattern='[+]?[0-9]{10,15}' maxlength='16' required placeholder='10–15 digits, optional + prefix'></label></div><div class='pair'><label>Opening deposit (INR)<input name='opening' type='number' min='0' max='999999999999.99' step='0.01' required></label><label>Minimum balance (INR)<input name='minimum' type='number' min='0' max='999999999999.99' step='0.01' required></label></div><p class='hint'>The opening deposit must be at least the minimum balance.</p><button>Open account →</button></form></div></section><section id='transactions' class='tab-panel form-panel'><div class='form-intro'><span class='quick-icon'>⇄</span><p class='eyebrow'>MONEY MOVEMENT</p><h2>Every transaction. In one place.</h2><p>Deposit, withdraw, or transfer funds between accounts with an immediate balance update.</p><div class='form-note'><strong>A record of every movement</strong><p>Successful transactions are saved to the account's history. Transfers appear in both accounts.</p><a href='#history'>Explore transaction history →</a></div></div><div class='form-body'><h2>Make a transaction</h2><p class='hint'>Enter the customer's full name and card number. Select the account to use when a customer has multiple accounts.</p>");
        if (accounts.isEmpty()) h.append("<div class='empty'><p>Open an account to enable transactions.</p></div>");
        else {
            h.append(formStart("/transaction")).append("<label>Transaction type<select name='type' id='type'><option value='deposit'>Deposit</option><option value='withdraw'>Withdrawal</option><option value='transfer'>Transfer</option></select></label><label>Customer full name<input name='fullName' maxlength='100' autocomplete='off' required></label><label>Customer card number<input name='card' type='text' inputmode='numeric' pattern='[0-9]{12}' minlength='12' maxlength='12' autocomplete='off' required placeholder='12-digit card number'></label><label>Account / source account<select name='source' required>").append(options(accounts)).append("</select></label><div id='destination' hidden><label>Recipient full name<input name='recipientName' maxlength='100' autocomplete='off'></label><label>Recipient card number<input name='recipientCard' inputmode='numeric' pattern='[0-9]{12}' minlength='12' maxlength='12' autocomplete='off'></label><label>Destination account<select name='destination'>").append(options(accounts)).append("</select></label></div><label>Amount (INR)<input name='amount' type='number' min='0.01' max='999999999999.99' step='0.01' required></label><p class='hint'>Withdrawals and transfers must leave the minimum balance in the source account.</p><button>Submit transaction</button></form>");
        }
        h.append("</div></section><section id='history' class='tab-panel'><div class='section-title'><h2>Transaction history</h2><span>Times shown in IST</span></div>");
        if (accounts.isEmpty()) h.append("<p class='hint'>Account transactions will appear here.</p>");
        else {
            String number = selected == null ? accounts.get(0).number() : selected;
            Account chosen = bank.account(number);
            h.append("<form method='get' action='/#history' class='history-filter'><label>Account<select name='account'>");
            for (Account a : accounts) h.append("<option value='").append(a.number()).append(a.number().equals(number) ? "' selected>" : "'>").append(esc(a.number() + " — " + a.holder())).append("</option>");
            h.append("</select></label><button class='secondary'>View history</button></form><p class='hint'>Current balance: INR ").append(chosen.balance()).append(" · Minimum: INR ").append(chosen.minimumBalance()).append("</p>");
            List<Transaction> history = bank.history(number);
            if (history.isEmpty()) h.append("<p class='empty'>No transactions for this account.</p>");
            else {
                h.append("<div class='scroll'><table><thead><tr><th>ID / date</th><th>Type</th><th>From</th><th>To</th><th>Amount (INR)</th><th>Balance after (INR)</th></tr></thead><tbody>");
                for (Transaction t : history) h.append("<tr><td>T").append(t.id()).append("<small>").append(DATE.format(t.date())).append("</small></td><td><span class='tag'>").append(t.type()).append(t.type() == Transaction.Type.TRANSFER ? (number.equals(t.source()) ? " OUT" : " IN") : "").append("</span></td><td>").append(t.source() == null ? "—" : t.source()).append("</td><td>").append(t.destination() == null ? "—" : t.destination()).append("</td><td>").append(t.amount()).append("</td><td>").append(number.equals(t.source()) ? t.sourceBalance() : t.destinationBalance()).append("</td></tr>");
                h.append("</tbody></table></div>");
            }
        }
        h.append("</section><footer>Simple Banking System · Java OOP college project<span>Data is saved on this computer.</span></footer></main><script>const c=document.getElementById('customer');function customer(){const d=document.getElementById('new-customer');d.hidden=!!c.value;d.querySelectorAll('input').forEach(i=>{i.required=!c.value;i.disabled=!!c.value})}c.addEventListener('change',customer);customer();const t=document.getElementById('type');if(t){function type(){const d=document.getElementById('destination');d.hidden=t.value!=='transfer';d.querySelectorAll('input,select').forEach(i=>{i.disabled=d.hidden;i.required=!d.hidden})}t.addEventListener('change',type);type()}document.querySelectorAll('form[method=post]').forEach(f=>f.addEventListener('submit',()=>{f.querySelector('button:not([type=button])').disabled=true}));</script></body></html>");
        return h.toString().replace("<footer>", adminPanel() + formStart("/logout") + "<button class='secondary'>Sign out</button></form><footer>").replace("</body>", "<script>" + NAV_SCRIPT + "</script></body>");
    }
    private static void redirect(HttpExchange e, String location) throws IOException {
        e.getResponseHeaders().set("Location", location); e.sendResponseHeaders(303, -1);
    }
    private static void setSessionCookie(HttpExchange e, Authentication.Session session) {
        e.getResponseHeaders().set("Set-Cookie", "BANK_SESSION=" + session.id + "; Path=/; HttpOnly; SameSite=Strict" + ((e instanceof HttpsExchange || Boolean.parseBoolean(System.getenv("BANK_SECURE_COOKIES"))) ? "; Secure" : ""));
    }
    private String loginPage(boolean admin, String message) {
        StringBuilder h = new StringBuilder("<!doctype html><html lang='en'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>Simple Bank | Sign in</title><style>" + CSS + NEW_CSS + "</style></head><body><main class='wrap login-wrap'><a class='brand-link' href='/login'><span class='mark'>SB.</span><strong>simple<span class='brand-light'>bank</span></strong></a><section class='form-panel login-card'><div class='form-intro'><p class='eyebrow'>WELCOME TO SIMPLE BANK</p><h2>Your banking.<br>Your space.</h2><p>Access your accounts, manage your money, and keep track of every transaction.</p><div class='form-note'><strong>One place for everyday banking</strong><p>Customer access is limited to your own accounts. Bank administration has a separate sign-in.</p></div></div><div class='form-body'><div class='login-tabs'><a href='/login' class='").append(admin ? "" : "selected").append("'>Customer login</a><a href='/admin-login' class='").append(admin ? "selected" : "").append("'>Admin login</a></div><h2>").append(admin ? "Administrator sign in" : "Welcome back").append("</h2><p class='hint'>").append(admin ? "Enter your administrator credentials." : "Sign in with your full name and 12-digit card number.").append("</p>");
        if (message != null) h.append("<p role='alert' class='notice error'>").append(esc(message)).append("</p>");
        h.append(formStart(admin ? "/admin-login" : "/login"));
        if (admin) h.append("<label>Username<input name='username' autocomplete='username' maxlength='100' required></label><label>Password<input name='password' type='password' autocomplete='current-password' maxlength='128' required></label>");
        else h.append("<label>Full name<input name='fullName' autocomplete='username' maxlength='100' required></label><label>Credit card number<input name='card' type='password' inputmode='numeric' pattern='[0-9]{12}' minlength='12' maxlength='12' autocomplete='current-password' required placeholder='12-digit card number'></label>");
        h.append("<button class='login-submit'>Sign in →</button></form><p class='hint'>").append(admin ? "Restricted to bank administrators." : "Need an account or your card number? Contact the bank administrator.").append("</p></div></section><footer>Simple Banking System<span>Customer &amp; administrator access</span></footer></main></body></html>");
        return h.toString();
    }
    /** Customer HTML is rendered separately: admin records are never delivered to the browser. */
    private String customerPage(String message, boolean error, String selected) {
        String id = current.get().customerId;
        Customer customer = bank.customers().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
        List<Account> accounts = bank.accounts().stream().filter(a -> a.customerId().equals(id)).toList();
        Account chosen = accounts.stream().filter(a -> a.number().equals(selected)).findFirst().orElse(accounts.get(0));
        StringBuilder h = new StringBuilder("<!doctype html><html lang='en'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>Simple Bank | My accounts</title><style>" + CSS + NEW_CSS + "</style></head><body><header><div class='wrap brand'><a class='brand-link' href='/'><span class='mark'>SB.</span><strong>simplebank</strong></a><span class='local'>").append(esc(customer.name())).append("</span>").append(formStart("/logout")).append("<button class='secondary'>Sign out</button></form></div></header><div class='nav-shell'><nav class='wrap tabs' aria-label='Banking navigation'><a href='#dashboard' data-tab='dashboard'>My accounts</a><a href='#transactions' data-tab='transactions'>Transactions</a><a href='#history' data-tab='history'>History</a></nav></div><main class='wrap'><div class='title'><div><p class='eyebrow'>CUSTOMER BANKING</p><h1 id='page-title'>Welcome back.</h1><p id='page-description'>Your accounts and activity.</p></div></div>");
        if (message != null) h.append("<div role='alert' class='notice ").append(error ? "error" : "success").append("'>").append(esc(message)).append("</div>");
        h.append("<section id='dashboard' class='tab-panel'><h2>Your accounts</h2>");
        for (Account a : accounts) h.append("<a class='snapshot-row' href='/?account=").append(a.number()).append("#history'><span class='account-avatar'>SB</span><span><strong>").append(a.number()).append("</strong><small>").append(a.status()).append(" · Minimum INR ").append(a.minimumBalance()).append("</small></span><span class='snapshot-balance'><strong>INR ").append(a.balance()).append("</strong><small>View history →</small></span></a>");
        h.append("</section><section id='transactions' class='tab-panel'><h2>Make a transaction</h2><p class='hint'>Use your own name and card. For a transfer, enter the recipient's details and account number.</p>").append(formStart("/transaction")).append("<label>Transaction type<select id='type' name='type'><option value='deposit'>Deposit</option><option value='withdraw'>Withdrawal</option><option value='transfer'>Transfer</option></select></label><div class='pair'><label>Customer full name<input name='fullName' maxlength='100' required value='").append(esc(customer.name())).append("'></label><label>Customer card number<input name='card' type='password' inputmode='numeric' pattern='[0-9]{12}' maxlength='12' required></label></div><label>Source account<select name='source' required>").append(options(accounts)).append("</select></label><div id='destination' hidden><div class='pair'><label>Recipient full name<input name='recipientName' maxlength='100'></label><label>Recipient card number<input name='recipientCard' type='password' inputmode='numeric' pattern='[0-9]{12}' maxlength='12'></label></div><label>Destination account number<input name='destination' placeholder='A100001' maxlength='30'></label></div><label>Amount (INR)<input name='amount' type='number' min='0.01' max='999999999999.99' step='0.01' required></label><p class='hint'>Minimum balance and account status rules apply.</p><button>Submit transaction</button></form></section><section id='history' class='tab-panel'><h2>Transaction history</h2><form method='get' action='/#history'><label>Account<select name='account'>");
        for (Account a : accounts) h.append("<option value='").append(a.number()).append(a.number().equals(chosen.number()) ? "' selected>" : "'>").append(a.number()).append("</option>");
        h.append("</select></label><button>View history</button></form><p class='hint'>Times shown in IST. Current balance: INR ").append(chosen.balance()).append("</p><div class='scroll'><table><thead><tr><th>Date / ID</th><th>Type</th><th>From</th><th>To</th><th>INR</th><th>Balance after</th></tr></thead><tbody>");
        for (Transaction t : bank.history(chosen.number())) h.append("<tr><td>").append(DATE.format(t.date())).append("<small>T").append(t.id()).append("</small></td><td>").append(t.type()).append("</td><td>").append(t.source() == null ? "—" : t.source()).append("</td><td>").append(t.destination() == null ? "—" : t.destination()).append("</td><td>").append(t.amount()).append("</td><td>").append(chosen.number().equals(t.source()) ? t.sourceBalance() : t.destinationBalance()).append("</td></tr>");
        h.append("</tbody></table></div></section><footer>Simple Banking System<span>Signed in as ").append(esc(customer.name())).append("</span></footer></main><script>const type=document.getElementById('type');function update(){const d=document.getElementById('destination');d.hidden=type.value!=='transfer';d.querySelectorAll('input').forEach(i=>{i.disabled=d.hidden;i.required=!d.hidden})}type.addEventListener('change',update);update();window.addEventListener('hashchange',()=>{if(!['#dashboard','#transactions','#history'].includes(location.hash))location.hash='dashboard'});</script><script>").append(NAV_SCRIPT).append("</script></body></html>");
        return h.toString();
    }
    private String adminPanel() {
        StringBuilder h = new StringBuilder("<div id='admin' class='tab-panel'><div class='info-strip'><span class='info-icon'>i</span><div><strong>Local operator administration</strong><p>Manage this bank's records. Customer IDs, account numbers, balances, and financial history are protected from direct editing.</p></div></div><section><div class='section-title'><h2>Customer directory</h2><span>Expand a customer to edit their details</span></div>");
        if (bank.customers().isEmpty()) h.append("<p class='empty'>No customers yet. Open an account to register a customer.</p>");
        for (Customer c : bank.customers()) {
            // Card numbers remain strings so they are never formatted as monetary/numeric values.
            h.append("<details class='admin-record'><summary>").append(esc(c.id() + " · " + c.name())).append(" <span class='tag'>").append(c.accountNumbers().size()).append(" accounts</span></summary>").append(formStart("/admin"))
                .append("<input type='hidden' name='action' value='customer'><input type='hidden' name='id' value='").append(c.id()).append("'><div class='pair'><label>Customer name<input name='name' maxlength='100' required value='").append(esc(c.name())).append("'></label><label>Customer email<input name='email' type='email' maxlength='254' required value='").append(esc(c.email())).append("'></label><label>Phone number<input name='phone' type='tel' pattern='[+]?[0-9]{10,15}' maxlength='16' required value='").append(esc(c.phone())).append("'></label></div><div class='card-display'><label>Customer card number<input readonly value='").append(c.cardNumber()).append("' aria-label='Card number for ").append(c.id()).append("'></label><button type='button' class='secondary copy-card' data-card='").append(c.cardNumber()).append("'>Copy card number</button><span class='copy-status' role='status'></span></div><p class='hint'>").append(c.phone().isEmpty() ? "Phone number needed for this existing customer. " : "").append("Linked accounts: ").append(esc(String.join(", ", c.accountNumbers()))).append(". Name changes update all linked accounts. The card number stays the same.</p><button type='submit'>Save customer</button></form></details>");
        }
        h.append("</section><section><div class='section-title'><h2>Account controls</h2><span>Freeze, reactivate, or close an account</span></div><p class='hint'>Frozen accounts cannot send or receive funds. To close an account, set the minimum to zero, withdraw or transfer its balance, then close it. Closed accounts remain available in history and cannot be reopened.</p>");
        if (bank.accounts().isEmpty()) h.append("<p class='empty'>No accounts to manage.</p>");
        for (Account a : bank.accounts()) {
            h.append("<details class='admin-record'><summary>").append(esc(a.number() + " · " + a.holder())).append(" <span class='tag'>").append(a.status()).append("</span></summary><p class='hint'>Balance: INR ").append(a.balance()).append(" · Customer: ").append(a.customerId()).append("</p>").append(formStart("/admin")).append("<input type='hidden' name='action' value='account'><input type='hidden' name='id' value='").append(a.number()).append("'><div class='pair'><label>Minimum balance (INR)<input name='minimum' type='number' min='0' step='0.01' max='999999999999.99' required value='").append(a.minimumBalance()).append("'></label><label>Account status<select name='status'>");
            for (String status : List.of("Active", "Frozen", "Closed")) h.append("<option").append(status.equals(a.status()) ? " selected" : "").append(">").append(status).append("</option>");
            h.append("</select></label></div><p class='hint'>Minimum balance must not exceed the current balance. Changes take effect immediately.</p><button>Save account settings</button> <a href='/?account=").append(a.number()).append("#history'>View history →</a></form></details>");
        }
        h.append("</section><section><div class='section-title'><h2>Administrative activity</h2><span>Newest first · timestamps in UTC</span></div>");
        if (bank.adminLog().isEmpty()) h.append("<p class='hint'>Customer and account changes will be recorded here.</p>");
        for (String entry : bank.adminLog()) h.append("<p class='audit-entry'>").append(esc(entry)).append("</p>");
        return h.append("</section></div>").toString();
    }
    private static String options(List<Account> accounts) {
        StringBuilder result = new StringBuilder();
        for (Account a : accounts) if (a.status().equals("Active")) result.append("<option value='").append(a.number()).append("'>").append(esc(a.number() + " — " + a.holder())).append("</option>");
        return result.toString();
    }
    private static Map<String, String> parse(String text) {
        Map<String, String> values = new HashMap<>();
        if (text != null && !text.isBlank()) for (String pair : text.split("&")) {
            String[] parts = pair.split("=", 2);
            values.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
        }
        return values;
    }
    private static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"); }
    private static void send(HttpExchange e, int status, String html) throws IOException {
        String nonce = UUID.randomUUID().toString();
        html = html.replace("<script>", "<script nonce='" + nonce + "'>").replace("<style>", "<style nonce='" + nonce + "'>");
        e.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'; script-src 'nonce-" + nonce + "'; style-src 'nonce-" + nonce + "'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'");
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        e.getResponseHeaders().set("Cache-Control", "no-store");
        e.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        e.getResponseHeaders().set("X-Frame-Options", "DENY");
        e.sendResponseHeaders(status, bytes.length); e.getResponseBody().write(bytes);
    }
    /** Hash navigation preserves form input and supports browser back/forward and direct links. */
    private static final String NAV_SCRIPT = """
        document.querySelectorAll('.copy-card').forEach(button=>button.addEventListener('click',async()=>{const status=button.parentElement.querySelector('.copy-status');try{await navigator.clipboard.writeText(button.dataset.card);status.textContent='Card number copied.'}catch(e){const input=button.parentElement.querySelector('input');input.focus();input.select();status.textContent='Select and copy the card number using Ctrl+C (or Command+C).'}}));
        const views={admin:['Bank administration.','Manage customer details, account settings, and administrative activity.'],dashboard:['A clear view of your bank.','Your accounts, balances, and everyday banking. All in one place.'],accounts:['Accounts, at a glance.','Manage customer accounts and find the details you need.'],'open-account':['Open a new account.','A simple start for every customer.'],transactions:['Move money with confidence.','Deposits, withdrawals, and account-to-account transfers.'],history:['Every transaction tells a story.','A complete record of money moving in and out.']};
        function navigate(){let id=location.hash.slice(1);if(!views[id])id=new URLSearchParams(location.search).has('account')?'history':location.pathname==='/admin'?'admin':location.pathname==='/transaction'?'transactions':location.pathname==='/open'?'open-account':'dashboard';if(!document.getElementById(id))id='dashboard';document.querySelectorAll('.tab-panel').forEach(p=>p.hidden=p.id!==id);document.querySelectorAll('[data-tab]').forEach(a=>{const active=a.dataset.tab===id;a.classList.toggle('active',active);if(active)a.setAttribute('aria-current','page');else a.removeAttribute('aria-current')});document.getElementById('page-title').textContent=views[id][0];document.getElementById('page-description').textContent=views[id][1];window.scrollTo(0,0)}
        document.querySelectorAll('[data-operation]').forEach(a=>a.addEventListener('click',()=>{const select=document.getElementById('type');if(select){select.value=a.dataset.operation;select.dispatchEvent(new Event('change'))}}));
        window.addEventListener('hashchange',navigate);window.addEventListener('load',()=>window.scrollTo(0,0));navigate();
        """;
    private static final String NEW_CSS = """
        .login-wrap{max-width:1060px;padding-top:54px}.login-card{margin-top:34px;min-height:490px}.login-tabs{display:flex;gap:22px;margin-bottom:32px;border-bottom:1px solid #e2e8e3}.login-tabs a{padding:0 0 12px;color:#7a8877;font-size:13px}.login-tabs .selected{color:#12624e;border-bottom:2px solid #12624e;font-weight:600}.login-submit{width:100%;margin-top:26px!important}.login-card .form-intro h2{font-size:38px;margin-top:30px}
        .card-display{margin-top:18px;padding:16px;background:#f4f7f0;border:1px solid #dce6d5;border-radius:8px}.card-display label{margin-top:0}.card-display input{font-family:monospace;font-size:18px;letter-spacing:2px}.card-display button{margin-top:12px}.copy-status{display:block;font-size:12px;margin-top:8px;color:#12624e}
        .admin-record{border-top:1px solid #e2e8e3;padding:18px 0}.admin-record summary{cursor:pointer;font-size:14px;font-weight:600;padding:6px 0}.admin-record summary .tag{margin-left:12px}.admin-record form{max-width:800px;padding:0 8px 10px}.admin-record form>a{font-size:12px;margin-left:15px}.audit-entry{border-bottom:1px solid #e2e8e3;padding:12px 0;font-size:12px;overflow-wrap:anywhere}
        :root{--ink:#142f2b;--green:#12624e;--line:#e2e8e3}body{background:#f5f6f3;color:var(--ink);font-family:'Segoe UI',Arial,sans-serif}header{border-color:var(--line)}.wrap{max-width:1240px;padding:0 36px}.brand{height:94px;gap:16px}.brand-link{display:flex;gap:12px;align-items:center;text-decoration:none;color:var(--ink)}.brand-link strong{font-size:28px;letter-spacing:-1.3px}.brand-light{font-weight:400}.brand-link small{font-size:9px;letter-spacing:2.1px;margin-top:1px}.mark{background:var(--ink);padding:10px 12px;font-size:20px;border-radius:12px;letter-spacing:-1px}.brand-dot{color:#c6e696}.local{font-size:12px;color:#687871;display:flex;align-items:center;gap:8px}.status-dot{width:7px;height:7px;border-radius:50%;background:#4b8a64}.operator{margin-left:18px;background:#eef1e8;border:1px solid #e0e5d8;border-radius:50%;width:38px;height:38px;display:grid;place-items:center;font-size:12px;font-weight:700}.operator-label strong{font-size:12px}.operator-label small{font-size:11px}.nav-shell{background:#fff;border-bottom:1px solid var(--line)}.tabs{display:flex;gap:32px;overflow-x:auto}.tabs a{padding:19px 2px 16px;color:#728079;text-decoration:none;white-space:nowrap;font-size:13px;font-weight:600;border-bottom:3px solid transparent}.tabs a:hover{color:var(--green)}.tabs a.active{color:var(--green);border-color:var(--green)}.title{margin:36px 0 28px}.title h1{font-size:32px;letter-spacing:-1px;font-weight:600}.title p{font-size:13px;color:#78847d}.title .eyebrow{font-size:10px;color:#688374;letter-spacing:1.8px}.button,button{background:var(--green);border-color:var(--green);border-radius:7px;padding:13px 19px;font-family:inherit;font-size:12px;font-weight:600;transition:background .15s}.button:hover,button:hover{background:#0c483a}.secondary{background:white;color:var(--green)}a{color:var(--green);text-decoration:none}a:hover{text-decoration:underline}a:focus-visible,button:focus-visible{outline:3px solid #85bca2;outline-offset:4px}.dashboard-grid{display:grid;grid-template-columns:1.3fr 1fr;gap:24px;margin-bottom:24px}.balance-card{background:linear-gradient(115deg,#163e33,#246552);border-radius:14px;padding:26px 30px;color:white;position:relative;overflow:hidden}.balance-card:after{content:'';position:absolute;right:-80px;top:-115px;width:300px;height:300px;border:1px solid #ffffff15;border-radius:50%;box-shadow:0 0 0 35px #ffffff04,0 0 0 70px #ffffff03;pointer-events:none}.balance-top{display:flex;justify-content:space-between;align-items:center;font-size:10px;letter-spacing:2px;color:#c9ddcb}.card-symbol{font-size:25px}.balance-card p{color:#bbd2c5;font-size:12px;margin:16px 0 3px}.balance-amount{font-size:37px;font-weight:600;letter-spacing:-1px;overflow-wrap:anywhere}.balance-amount>span{font-size:16px;font-weight:400;letter-spacing:0;color:#c8decb}.balance-bottom{display:flex;justify-content:space-between;align-items:flex-end;border-top:1px solid #ffffff20;margin-top:22px;padding-top:18px;font-size:12px}.balance-bottom small{color:#afc9bb;font-size:10px;margin-top:3px}.balance-bottom a{color:#d9edb8;font-size:11px}.stats{grid-template-columns:1fr 1fr;gap:18px;margin:0}.stats>div{border-color:var(--line);border-radius:14px;padding:26px 22px;display:flex;flex-direction:column;justify-content:center}.stats small{font-size:10px;letter-spacing:1px;color:#738377}.stats strong{font-size:42px;line-height:1.1;margin:18px 0 12px;color:var(--ink);font-weight:500}.stats p{font-size:11px;color:#89928c}section{border-color:var(--line);border-radius:12px;padding:25px 28px}h2{font-size:17px;font-weight:600;color:var(--ink)}.section-title span,.section-title>a{font-size:11px}.quick-section{background:transparent;border:none;padding:5px 0 0}.quick-actions{display:grid;grid-template-columns:repeat(4,1fr);gap:16px;margin-top:14px}.quick-actions>a{display:flex;flex-direction:column;position:relative;background:white;border:1px solid var(--line);padding:21px;border-radius:11px;color:var(--ink);text-decoration:none;transition:border-color .15s,transform .15s}.quick-actions>a:hover{border-color:#88aa95;transform:translateY(-2px)}.quick-icon{display:grid;place-items:center;width:38px;height:38px;background:#eef3e8;border-radius:10px;color:var(--green);font-size:25px;font-weight:400;margin-bottom:18px}.quick-actions strong{font-size:13px;font-weight:600}.quick-actions small{font-size:10px;margin-top:5px;color:#839086}.quick-actions b{position:absolute;right:20px;top:26px;font-size:17px;font-weight:400;color:#a2afa5}.snapshot-row{display:flex;align-items:center;gap:14px;padding:17px 0;border-bottom:1px solid #edf0eb;color:var(--ink);text-decoration:none}.snapshot-row:last-child{border:0;padding-bottom:0}.snapshot-row:hover{color:var(--green)}.snapshot-row strong{font-size:13px;font-weight:600}.snapshot-row small{font-size:11px;margin-top:4px}.account-avatar{width:40px;height:40px;border-radius:10px;background:#f1f3ed;display:grid;place-items:center;font-size:11px;color:#68826c;font-weight:600}.snapshot-balance{margin-left:auto;text-align:right}.info-strip{display:flex;align-items:center;gap:16px;padding:18px 22px;background:#edf1e7;border:1px solid #e0e7d8;border-radius:10px;margin-bottom:28px}.info-strip strong{font-size:12px;font-weight:600}.info-strip p{font-size:11px;margin:3px 0 0;color:#7a8877}.info-strip>a{margin-left:auto;font-size:11px}.info-icon{border:1px solid #7a9272;border-radius:50%;width:23px;height:23px;display:grid;place-items:center;font-family:Georgia,serif}.form-panel{display:grid;grid-template-columns:.85fr 1.4fr;overflow:hidden;padding:0;min-height:450px}.form-intro{background:#edf2e9;padding:40px 34px}.form-intro h2{font-size:27px;line-height:1.3;letter-spacing:-.7px;margin:18px 0}.form-intro p{font-size:13px;color:#75816f}.form-intro .eyebrow{font-size:10px;margin-top:30px}.form-note{border-top:1px solid #d8e0d0;margin-top:42px;padding-top:22px;font-size:12px}.form-note p{font-size:12px}.form-note a{display:block;margin-top:16px}.form-body{padding:36px 40px}.form-body form>button{margin-top:8px}label{font-size:12px;font-weight:600;color:#4b5c50;margin-top:20px}input,select{padding:13px 12px;border-color:#dce3db;border-radius:7px;color:var(--ink);font-family:inherit;font-size:13px}input:focus,select:focus{outline-color:#85bca2}.hint{color:#819080;font-size:12px}.history-filter{padding:17px;background:#f5f7f2;border-radius:9px;margin:18px 0}.history-filter label{margin-top:0}th{background:#f5f7f2;font-size:10px;text-transform:uppercase;letter-spacing:.6px;color:#748372}td,th{padding:18px 14px;border-color:#edf0e9}td{font-size:12px}td small{font-size:10px;margin-top:4px}.tag{background:#edf3e8;color:#4a714c;border-radius:5px}footer{border-top:1px solid var(--line);padding-top:20px;color:#93a08f;font-size:10px}.notice{font-size:13px}.empty{padding:40px 16px}.empty p{font-size:13px}.empty .button{margin-top:10px}@media(min-width:1500px){.wrap{max-width:1360px}}@media(max-width:950px){.quick-actions{grid-template-columns:repeat(2,1fr)}.dashboard-grid{grid-template-columns:1fr}.stats>div{padding:20px}.stats strong{font-size:32px;margin:10px 0}.form-intro{padding:26px}.form-body{padding:28px}.operator-label{display:none}}@media(max-width:760px){.wrap{padding:0 20px}.brand{height:78px}.brand-link strong{font-size:25px}.brand-link small{font-size:8px}.operator{margin-left:auto}.local{display:none}.tabs{gap:24px}.tabs a{font-size:12px;padding:16px 0}.title{margin:26px 0 22px;gap:14px}.title h1{font-size:27px}.title .button{align-self:flex-start}.stats{grid-template-columns:1fr 1fr}.balance-card{padding:23px}.balance-amount{font-size:33px}.form-panel{grid-template-columns:1fr}.form-intro{padding:24px}.form-intro .quick-icon,.form-note{display:none}.form-intro .eyebrow{margin:0}.form-intro h2{font-size:22px;margin:10px 0}.form-body{padding:24px}.section-title{flex-direction:row;align-items:baseline}.info-strip{align-items:flex-start}.info-strip>a{display:none}.quick-actions{gap:12px}.quick-actions>a{padding:17px}.quick-actions small{font-size:10px}.snapshot-row{gap:10px}.snapshot-row strong{font-size:12px}.snapshot-row small{font-size:10px}.account-avatar{width:32px;height:32px}.history-filter{align-items:stretch}.history-filter button{align-self:flex-start}footer{margin-top:20px;gap:6px}.pair{grid-template-columns:1fr}section{padding:20px}.form-panel{padding:0}}@media(prefers-reduced-motion:reduce){*{transition:none!important}}
        """;
    private static final String CSS = """
        *{box-sizing:border-box}body{margin:0;background:#f5f7fa;color:#263449;font:15px/1.5 Arial,sans-serif}header{background:white;border-bottom:1px solid #dce3eb}.wrap{max-width:1140px;margin:auto;padding:0 26px}.brand{display:flex;align-items:center;gap:13px;height:86px}.brand strong{font-size:19px}.brand small{color:#69768a}.mark{background:#244c74;color:white;border-radius:7px;padding:10px;font-weight:bold}.local{margin-left:auto;color:#69768a;font-size:13px}.title{display:flex;justify-content:space-between;align-items:center;margin:34px 0 26px;gap:20px}h1{font-size:30px;margin:5px 0}h2{font-size:19px;margin:0 0 12px}h3{font-size:17px;margin:0}p{margin:8px 0;color:#647185}.eyebrow{font-size:11px;letter-spacing:1.4px;font-weight:bold;color:#53718c;margin:0 0 8px}.stats{display:grid;grid-template-columns:1fr 1fr 1.4fr;gap:18px;margin-bottom:24px}.stats>div,section{background:white;border:1px solid #dce3eb;border-radius:8px}.stats>div{padding:20px 24px}.stats strong{display:block;font-size:26px;margin-top:6px;color:#244c74}small{display:block;font-size:12px;font-weight:normal;color:#69768a}section{padding:24px;margin-bottom:24px;scroll-margin-top:20px}.section-title{display:flex;justify-content:space-between;align-items:baseline;gap:12px}.section-title span{font-size:12px;color:#69768a}.columns{display:grid;grid-template-columns:1fr 1fr;gap:24px}label{display:block;font-size:13px;font-weight:bold;margin:15px 0 0}input,select{width:100%;font:14px Arial,sans-serif;margin-top:7px;padding:11px;border:1px solid #c9d3df;border-radius:5px;background:white;color:#263449}input:focus,select:focus{outline:2px solid #719bc4;outline-offset:1px}.pair{display:grid;grid-template-columns:1fr 1fr;gap:14px}.hint{font-size:13px;margin:10px 0 16px}button,.button{display:inline-block;border:1px solid #244c74;border-radius:5px;background:#244c74;color:white;padding:11px 17px;font:bold 13px Arial,sans-serif;text-decoration:none;cursor:pointer}button:hover,.button:hover{background:#193c60;color:white}button:disabled{opacity:.5;cursor:wait}.secondary{background:white;color:#244c74}.empty{padding:30px 12px;text-align:center;color:#647185}.scroll{overflow-x:auto}table{border-collapse:collapse;width:100%;font-size:13px;text-align:left}th{background:#f5f7fa;color:#647185;font-size:11px;letter-spacing:.25px}th,td{padding:14px 12px;border-bottom:1px solid #e8edf2;white-space:nowrap}a{color:#245d8c}.tag{font-size:10px;background:#edf3f8;color:#365b7c;border-radius:4px;padding:5px 7px}.history-filter{display:flex;align-items:flex-end;gap:12px}.history-filter label{max-width:400px;flex:1;margin-top:0}.history-filter button{margin-bottom:1px}.notice{padding:14px 18px;border-radius:5px;margin-bottom:20px}.success{background:#eaf5ef;border:1px solid #a7d5b8;color:#22613b}.error{background:#fff0ef;border:1px solid #e7b4b0;color:#923a32}footer{font-size:12px;color:#748195;padding:4px 0 28px;display:flex;justify-content:space-between;gap:12px}[hidden]{display:none!important}@media(max-width:760px){.wrap{padding:0 16px}.title{align-items:flex-start;flex-direction:column}.local{display:none}.stats{grid-template-columns:1fr;gap:10px}.stats>div{padding:14px 18px}.stats strong{font-size:23px}.columns{grid-template-columns:1fr;gap:0}.pair{grid-template-columns:1fr}.section-title{align-items:flex-start;flex-direction:column;margin-bottom:12px}section{padding:18px}.history-filter{align-items:stretch;flex-direction:column}footer{flex-direction:column}h1{font-size:27px}}
        """;
}
