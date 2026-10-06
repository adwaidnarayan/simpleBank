# Deploy on Render with SQLite

1. Push the project source, Dockerfile, .dockerignore, and render.yaml to your Git repository. Do not commit local bank records, keys, or lib/out folders.
2. In Render, create a Blueprint from that repository and review the resources in render.yaml.
3. The Blueprint provisions a Docker web service on the Starter plan and a 1 GB persistent disk at /var/data. These are paid resources; Render's free ephemeral filesystem is unsuitable for this database.
4. Deploy. Open the HTTPS Render URL. /health returns 200 without a login. Render handles the external TLS certificate; BANK_SECURE_COOKIES=true ensures browser session cookies require HTTPS.

## Persistence

- Database: `/var/data/bank.db` (BANK_DB_PATH).
- Encryption keys: `/var/data/keys` (configured in the Docker entrypoint).
- Optional legacy import: `/var/data/bank.dat` (BANK_DATA_FILE).
- Local defaults: `data/bank.db`, with one-time import from `data/bank.dat`.

The application uses Xerial SQLite JDBC 3.53.4.0. SQLite's bank_state table holds one AES-256-GCM encrypted aggregate containing customers, accounts, financial history, counters, and administrative logs. It uses a prepared statement and a SQL transaction for each save, with FULL synchronous durability and a revision check to prevent stale writers from overwriting updates. This is real SQLite storage; it deliberately preserves encrypted aggregate storage rather than exposing personal fields as plaintext SQL columns. Consequently it is intended for a small, single-instance project, not SQL reporting or horizontal scaling.

All state is loaded into the application, so run exactly one service instance against the database. Existing SQLite state takes precedence over the legacy file. Legacy import preserves the original encrypted file and card numbers; a missing legacy decryption key stops import rather than creating an empty bank. Do not put a .dat file at the .db path.

Back up the database while the service is stopped, plus its encryption key directory separately. Keep the database path unchanged on restore: encryption key filenames are derived from that absolute path. Local encrypted records cannot be copied to Render without transferring the matching key and adapting its filename to the new absolute path as described in SECURITY.md. A fresh Render deployment starts empty; create customers through Admin.

## Local Docker usage

```text
docker build -t simple-bank .
docker run --rm -p 8080:10000 -v simple-bank-data:/var/data simple-bank
```

Then open http://localhost:8080. The container runs as the bank user (UID 10001). If supplying a host-mounted directory, give UID 10001 write access to that directory first. The image build downloads the SQLite driver from Maven Central and validates its published SHA-256 checksum. It includes only source-built classes and the driver, not local customer data or keys.

The local scripts download the driver once into lib; the first run needs internet access. Subsequent runs work offline.

The assignment admin credentials remain admin / admin123. This shared default and card-based customer authentication are appropriate only for the stated project, not real banking data on a public service.

References: [Render Docker](https://render.com/docs/docker), [persistent disks](https://render.com/docs/disks), [Xerial SQLite JDBC](https://github.com/xerial/sqlite-jdbc).
