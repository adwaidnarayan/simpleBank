# Design Document: Simple Banking System

Storage update: SqliteBankRepository is now the application's BankRepository implementation. It uses SQLite JDBC and a bank_state table with singleton ID, revision, encrypted payload, and update timestamp. A database transaction commits all banking state together; optimistic revision matching rejects stale writers. Payloads retain AES-256-GCM protection. FileBankRepository remains for one-time legacy import and recovery. See RENDER.md for deployment details. Earlier file-storage descriptions below describe the compatibility repository.

## 1. Objective and scope

Implement the assigned basic banking operations using object-oriented Java and a simple website. The system maintains customers, associated accounts, minimum balances, and durable transaction logs. A single operator uses the website on the same computer. All monetary values use INR, and history dates are displayed in Indian Standard Time.

## 2. Architecture

Browser forms → BankApplication → BankSystem → Account / Customer / Transaction.

BankSystem → BankRepository interface → FileBankRepository → local data file.

The browser displays server-rendered HTML and submits forms. JavaScript only adjusts relevant fields and disables a submitted button; banking rules execute on the Java server. Java's built-in HttpServer listens on loopback address 127.0.0.1. No third-party libraries are needed.

## 3. Classes, attributes, and methods

| Class | Attributes | Main methods and responsibility |
|---|---|---|
| Account | number, customerId, holder, balance, minimumBalance | Read accessors expose details. Package-private credit(amount) adds funds. debit(amount) rejects any result below minimumBalance. |
| Customer | id, name, email, accountNumbers | Accessors return customer details; accountNumbers() returns a defensive list; addAccount(number) links an account. |
| Transaction | id, type, amount, date, source, destination, sourceBalance, destinationBalance | Immutable Java record with accessors. Type enum contains DEPOSIT, WITHDRAWAL, TRANSFER. Captures balances after the operation. |
| BankSystem | repository, state | openAccount(...) validates and creates accounts; transact(...) processes operations; history(number) filters the log; accounts(), customers(), account(number) provide views; money(...) validates monetary input. Private log(...) creates entries; copy() supports rollback. |
| BankSystem.State | customers, accounts, transactions, nextCustomer, nextAccount, nextTransaction | Serializable aggregate holding all persistent records and ID counters. |
| BankRepository | None | Interface declaring load() and save(state); abstracts storage. |
| FileBankRepository | file | load() reads a validated serialized state; save(state) writes a temporary file and atomically replaces the saved state. |
| BankApplication | bank, token, submissions | main(...) starts the server; handle(...) routes requests; page(...) renders the interface; formStart(...) adds anti-forgery and submission tokens; parse(...), esc(...), send(...) handle HTTP input/output. |
| BankSystemTest | checks | Exercises business rules, rollback, and persistence using temporary storage. |
| WebsiteTest | CLIENT, base, checks | Starts an isolated server, submits HTTP forms, and verifies real responses and saved balances. |

## 4. Relationships and OOP principles

- One Customer has zero or more account numbers; account creation ensures each new customer receives an account. Each Account belongs to exactly one Customer.
- BankSystem owns the collection of customers, accounts, and transaction logs.
- One Account participates in many Transactions. A deposit references a destination; a withdrawal references a source; a transfer references both.
- Encapsulation: Account balances are private; public consumers cannot set them. Debit rules reside in the Account class.
- Abstraction: BankSystem exposes banking operations without exposing storage details; BankRepository specifies the persistence contract.
- Interface implementation and polymorphism: FileBankRepository implements BankRepository. Tests substitute a repository that fails on save to verify rollback without changing BankSystem.
- Composition: BankSystem coordinates domain objects instead of forcing unrelated banking objects into an inheritance hierarchy.
- Immutability: Transaction records, BigDecimal amounts, and Instant timestamps preserve the recorded transaction details.

## 5. Rules and processing

### Account opening

Choose an existing customer or provide a new name and unique email. Validate name length and email format. Parse opening deposit and minimum balance as non-negative amounts with at most two decimal places. Reject an opening balance below the minimum. Generate customer/account IDs automatically, link the account to its owner, and log a positive opening deposit. A zero opening balance produces no artificial transaction.

### Deposit and withdrawal

Require an existing account and a positive amount. Deposits credit the balance. Withdrawals call Account.debit; the resulting balance must be greater than or equal to the minimum. Successful operations receive a unique log ID and timestamp.

### Transfer

Validate two existing, different accounts and a positive amount. Debit the source subject to its minimum balance, credit the destination, and record one transfer visible in both histories. Record each account's post-transfer balance.

### Persistence and consistency

BankSystem operations are synchronized. Before mutation, copy the aggregate state. Save the complete updated state through the repository. On validation or save failure, restore the snapshot; no rejected operation remains in the log. Atomic replacement avoids partially replacing the previous file. If the filesystem does not support atomic moves, saving fails visibly rather than claiming success. Corrupt data stops startup and is not replaced by an empty bank.

BigDecimal represents money exactly; binary floating point is never used. Amount input accepts up to 12 whole-number digits and two decimal places. Persistent counters prevent ID reuse after restart.

## 6. Web routes and usability

The frontend uses five hash-addressable navigation views: #dashboard, #accounts, #open-account, #transactions, and #history. JavaScript shows the selected panel and marks the active navigation link with aria-current. Quick actions preselect deposit or transfer. Successful submissions redirect to the relevant tab. Dashboard balances and account snapshots are rendered from the saved bank data.

| Request | Behavior |
|---|---|
| GET / | Account overview, customer/account form, transaction form, and history |
| GET /?account=A100001 | History for the chosen account |
| POST /open | Open account; redirect to overview on success |
| POST /transaction | Deposit, withdrawal, or transfer; redirect on success |

Validation failures show a readable message and return HTTP 400. Form tokens reject forged or repeated submissions. POST/Redirect/GET prevents browser refresh from repeating successful transactions. Text is HTML escaped. Forms have visible labels and browser validation; narrow screens stack the sections. Rejected forms require re-entry.

## Administration extension

Customer additionally stores phone and cardNumber. New customers require a validated phone number. SecureRandom generates 12 digits with a nonzero first digit, checking existing card numbers for uniqueness. Card assignment occurs within account creation's saved state and is immutable through the UI. Startup assigns and saves cards for older customer records while leaving unknown phone numbers blank. transactWithCard verifies the customer's name/card against account ownership, including the destination for transfers, before invoking package-private transaction processing. Admin exposes a readonly card field and a clipboard button with manual-copy fallback.

The Admin view posts customer and account changes to POST /admin using the same anti-forgery and one-time submission checks as financial forms. BankSystem.updateCustomer validates identity fields and updates every associated account holder. BankSystem.updateAccount validates minimum balances and Active/Frozen/Closed status transitions. Account credit/debit reject frozen and closed accounts, including transfer recipients. Closure requires zero balance and cannot be reversed. Both update methods snapshot and roll back on persistence failure. A persistent administrative change log records successful changes. Existing serialized records load with active status and an empty administrative log.

## 7. Scope and limitations

Authentication extension: Authentication owns server-side session records, session-bound CSRF/submission tokens, login cooldowns, and PBKDF2 verification of the configured administrator password. BankApplication serves separate `/login` and `/admin-login` forms, rotates cookies on successful login, and handles POST `/logout`. Anonymous requests redirect to login. Customer HTML is generated separately and filters by customer ID; history and transaction routes independently enforce ownership. Admin-only routes reject customer sessions. Account creation is administered by the bank.

The assignment now has customer and administrator login roles. It does not include interest calculations or external payment connectivity. A single process must own a data file. Storage uses Java serialization and full-state snapshots, appropriate to a small college project rather than large-scale banking. Backup while the application is stopped. Form tokens expire after restart and may expire after extensive browsing; reload before submitting again.
