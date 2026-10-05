# Test Cases and Results

Latest verification after login implementation: **100 automated checks passed** (36 banking, 56 HTTP, 8 encryption). New integration coverage verifies anonymous redirects, incorrect/correct admin credentials, customer login, logout and old-form rejection, absence of other customer/admin data in customer HTML, cross-customer history/transaction rejection, Admin-only route enforcement, and successful own-account transactions.

Verified on 5 October 2026, Windows, JDK 27. Command: `./test.ps1`.

**Result: all 76 automated checks passed — 36 business checks and 40 HTTP website checks.** The running empty website was also opened in a browser and its layout visually inspected.

After the frontend redesign, all 76 checks passed again. Browser checks confirmed navigation to Accounts, Open account, Transactions, and History; selecting Transfer displayed its destination selector. The dashboard and history displayed the existing saved account correctly.

## Banking sample inputs and expected outputs

Phone/card extension: 76 checks passed (36 business and 40 HTTP). Additional checks cover required and malformed phones, distinct 12-digit cards, card persistence, Admin copy-button output, missing credentials, wrong name/card, wrong account owner, and mismatched transfer recipient. Existing HTTP operation checks now submit matching name/card credentials.

Cases 1–9 follow the same initial state unless a case states otherwise. Test names and emails are isolated test inputs; the delivered application has no preloaded customers.

| # | Input / action | Expected output | Result |
|---|---|---|---|
| 1 | Start with a new file | No customers or accounts | Pass |
| 2 | New Alice, alice@example.com; opening 1000; minimum 500 | C1 / A100001; balance 1000.00; opening deposit logged | Pass |
| 3 | Existing C1; opening 500; minimum 100 | A100002; same customer owns two accounts | Pass |
| 4 | Deposit 250.25 to A100001 | Balance 1250.25 | Pass |
| 5 | Withdraw 750.25 from A100001 | Balance exactly 500.00; accepted | Pass |
| 6 | Withdraw 0.01 from A100001 | Rejected; balance 500.00 and history unchanged | Pass |
| 7 | Transfer 400 from A100002 to A100001 | Source 100.00; destination 900.00; one shared transfer ID | Pass |
| 8 | Transfer 1 more from A100002 | Rejected; recipient remains 900.00 | Pass |
| 9 | Restart with saved data | Balances restored; A100001 has four history entries | Pass |
| 10 | Transfer to same account | Rejected | Pass |
| 11 | Transfer to missing account / deposit to missing account | Rejected | Pass |
| 12 | Deposit 0, -1, 1.001, NaN, 1e3, empty input, or 1000000000000 | Each rejected | Pass |
| 13 | Opening deposit 99; minimum 100 | Account not created | Pass |
| 14 | Invalid email / blank customer name | Account not created | Pass |
| 15 | Create another customer with ALICE@example.com | Duplicate email rejected, ignoring case | Pass |
| 16 | Open account for missing customer | Rejected | Pass |
| 17 | New Bob; opening 0; minimum 0 | Account opens; no fictitious transaction | Pass |
| 18 | Repository throws on saving a transfer | Both balances and log roll back | Pass |
| 19 | Repository throws on saving a new account | Account and customer association roll back | Pass |
| 20 | Load corrupted data | Startup fails; data is not silently overwritten | Pass |

## Website integration cases

The WebsiteTest starts a separate server on a temporary local port with its own data file. It uses the real forms' anti-forgery and one-time submission values.

| Check | Expected output | Result |
|---|---|---|
| Start server and load / | HTTP 200 and empty-state content | Pass |
| Submit account form | HTTP 303; generated account visible | Pass |
| Submit the same form again | HTTP 409; no duplicate operation | Pass |
| Open account for existing customer | HTTP 303 | Pass |
| Submit deposit / withdrawal / transfer | HTTP 303 for each, saved balances updated | Pass |
| Withdraw below minimum | HTTP 400 with readable minimum-balance error | Pass |
| View sender and receiver histories | TRANSFER OUT / TRANSFER IN respectively | Pass |
| Submit without anti-forgery token | HTTP 403 | Pass |
| Request unknown history account | Handled HTTP 400 | Pass |
| Request unknown route | HTTP 404 | Pass |
| Supply HTML in a query message | HTML escaped rather than executed | Pass |
| Read website's saved file | Expected final source balance 500.00 | Pass |

## Manual acceptance procedure

Administration integration checks additionally cover customer editing and linked holder updates; freezing and reactivation; transaction rejection on frozen and closed accounts; transfer rollback for a frozen recipient; invalid minimum rejection; funded-account closure rejection; zero-balance closure; prevention of reopening; retained history; anti-forgery validation; invalid customer inputs; and persistent status/change logs. All passed.

1. Run the website with a separate data file if you want to preserve your own accounts.
2. Create two accounts using the account-opening form.
3. Deposit funds, withdraw down to the exact minimum, then attempt a withdrawal below it.
4. Transfer between the accounts; inspect each history using its account selector.
5. Choose an existing customer when opening a third account; verify customer count stays the same.
6. Restart the server and confirm balances and histories persist.
7. Narrow the browser window; forms should stack and tables should scroll horizontally.

The automated HTTP checks verify server behavior, while browser visual inspection verifies the rendered empty page. They do not claim exhaustive testing of every browser or operating system.
