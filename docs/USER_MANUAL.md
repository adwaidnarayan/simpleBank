# User Manual

## Login and access

The website now opens at `/login`. Customers enter their full name and 12-digit card number; names are matched without case sensitivity. Customer pages contain only their own accounts, balances, and history. Transfers to another customer require the recipient's full name, card number, and account number. These are entered manually so the site does not expose a customer directory.

Use `/admin-login` for administration. Username: `admin`. Password: `admin123` (the assignment credentials requested by the project owner). Administrators can create customers/accounts, manage records, and view all bank activity. Customer sessions cannot access these actions, even through direct requests. Admin and customer logins share one browser session; signing in as another role replaces it.

Use **Sign out** to end the session. Sessions expire after 30 minutes without activity or 8 hours total, and all sessions end when the server restarts. Five failed login attempts from the same address trigger a temporary one-minute cooldown. New customers must first be registered by Admin and obtain their card number there.

## Requirements

- JDK 17 or later, including both java and javac.
- A modern browser with JavaScript enabled.
- A writable project folder.

The project was compiled and tested on Windows with JDK 27. It uses Java 17-compatible language features and no external dependencies.

## Setup and startup

1. Copy the entire project folder to your computer.
2. Open a terminal in that folder. Check `java -version` and `javac -version`.
3. On Windows, double-click `run.bat` or run `./run.ps1` in PowerShell.
4. Visit http://127.0.0.1:8080 in your browser.
5. Keep the server terminal running. Use Ctrl+C in that terminal to stop it.

To run on another port, use `run.bat 8081`, then open http://127.0.0.1:8081.

Manual commands, also usable on Linux/macOS from the project directory:

```text
mkdir out
javac -encoding UTF-8 -d out src/bank/*.java
java -cp out bank.BankApplication
```

The optional second argument to BankApplication is a data-file path. For example, `java -cp out bank.BankApplication 8081 another-bank/bank.dat` runs a separate bank on port 8081.

## Open an account

The navigation bar contains five tabs: **Overview**, **Accounts**, **Open account**, **Transactions**, and **History**. Overview shows saved totals and account summaries. Its quick actions open the appropriate tab; Deposit funds and Transfer money also select the transaction type. Browser Back and Forward work with the tabs. Switching tabs preserves unfinished form input until the page is reloaded or submitted.

1. Go to **Open an account**.
2. Leave **Customer** set to **New customer** and enter a name and email. If the customer already exists, select them from the list; their name and email are reused.
3. Enter an opening deposit and a minimum balance in INR. Both may be zero, but the opening deposit must be at least the minimum.
4. Click **Open account**. The confirmation shows the generated account number, and the account appears in the table.

Customer IDs use C1, C2, and so on. Account numbers start at A100001. A customer can own multiple accounts. Duplicate emails are rejected; choose the existing customer to add another account.

## Deposit or withdraw

Every new customer must provide a phone number (10–15 digits, with an optional leading +). Registration automatically issues one unique, randomly generated 12-digit customer card number. In Admin, expand the customer to view it and use **Copy card number**. The number stays unchanged when details are edited or additional accounts are opened.

All deposits and withdrawals require the customer's full name and card number, plus the chosen account. Transfers require those details for both sender and recipient. The server checks that each name/card pair belongs to the selected account; names are matched without case sensitivity and with outer spaces ignored. Missing or mismatched details reject the transaction without changing balances.

Existing customers receive a card automatically on upgrade. Their original balances and history remain intact. Their phone numbers are not invented: add them in Admin. These are local project identifiers, not cards issued by a payment network. Customer and administrator sessions now use separate roles.

1. In **Make a transaction**, select **Deposit** or **Withdrawal**.
2. Select the account and enter a positive amount with at most two decimal places.
3. Click **Submit transaction**.
4. A success message confirms saving. The overview and history show the updated balance.

A withdrawal leaving less than the account's minimum balance is rejected. The balance and history remain unchanged.

## Transfer

1. Select **Transfer**.
2. Choose a source account and a different destination account.
3. Enter a positive amount and submit.
4. Both balances change together. The same transfer ID appears as **TRANSFER OUT** for the sender and **TRANSFER IN** for the receiver.

The source must retain its minimum balance. Transfers to the same account or a nonexistent account are rejected.

## View history

Click **View history** in an account's row, or select the account under **Transaction history** and press **View history**. Newest transactions appear first. Each entry includes an ID, date/time in IST, type, source/destination, amount, and the selected account's balance after that transaction. A positive opening deposit appears as a deposit.

## Storage and backup

**Encryption update:** bank files are now AES-256-GCM encrypted. Back up `%USERPROFILE%/.simple-bank-keys` separately along with the bank file. Restore both; never discard the keys. See SECURITY.md for moving paths and optional HTTPS. Older backup-only instructions below require this additional key backup.

## Administration

Open the **Admin** tab. Expand a customer to edit their name and email; the updated name appears on all linked accounts. Expand an account to change its minimum balance or status. The minimum cannot exceed the current balance. Frozen accounts cannot send or receive funds; change their status back to Active to resume operations.

To close an account, set its minimum balance to zero, withdraw or transfer its remaining funds using Transactions, then select Closed in Admin. Closure requires a zero balance and cannot be reversed. Closed accounts and their histories remain available. Balances and transaction records cannot be edited directly. Administrative activity records successful changes with UTC timestamps and persists after restart.

Admin requires a separate administrator login. Customer sessions cannot access administration.

Data is automatically saved to `data/bank.dat` after successful changes. Closing the browser does not erase records. Restarting the server loads saved customers, accounts, counters, and histories. There are no preloaded accounts.

To back up: stop the server and copy `data/bank.dat` to a safe folder. To restore: stop the server, keep a copy of the current file, and replace it with the backup. Do not edit the file manually or open it with two running server processes.

## Tests

Run `./test.ps1` in PowerShell. It compiles the code and runs the business-rule and HTTP integration tests. Expected result: 36 business checks and 40 website checks pass. Tests use temporary files and remove them afterward.

Manual alternative:

```text
javac -encoding UTF-8 -d out src/bank/*.java tests/bank/*.java
java -cp out bank.BankSystemTest
java -cp out bank.WebsiteTest
```

## Troubleshooting

| Problem | Action |
|---|---|
| java or javac is not found | Install a JDK, add its bin folder to PATH, and reopen the terminal. |
| Port is already in use | Stop the previous server or choose another port with a separate data file if both will run. |
| Browser cannot connect | Check the server terminal for startup errors and verify the address and port. |
| PowerShell blocks scripts | Use run.bat or the manual commands instead. |
| Minimum-balance error | Lower the withdrawal/transfer amount or make a deposit first. |
| Form expired or already submitted | Check the history, reload the page, and submit a fresh form only if needed. |
| Data could not be saved | Check folder permissions and disk space; the operation was rolled back. |
| Data cannot be read at startup | Preserve the file and restore a valid backup; the application will not silently erase it. |
