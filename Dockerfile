FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /build
COPY src/ src/
RUN mkdir lib && curl -fsSL https://repo.maven.apache.org/maven2/org/xerial/sqlite-jdbc/3.53.4.0/sqlite-jdbc-3.53.4.0.jar -o lib/sqlite-jdbc.jar \
    && curl -fsSL https://repo.maven.apache.org/maven2/org/xerial/sqlite-jdbc/3.53.4.0/sqlite-jdbc-3.53.4.0.jar.sha256 -o /tmp/jdbc.sha256 \
    && echo "$(cat /tmp/jdbc.sha256)  lib/sqlite-jdbc.jar" | sha256sum -c -
RUN mkdir out && javac --release 17 -encoding UTF-8 -d out src/bank/*.java

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN groupadd --gid 10001 bank && useradd --uid 10001 --gid bank --create-home bank \
    && mkdir -p /var/data && chown bank:bank /var/data
COPY --from=build --chown=bank:bank /build/out/ /app/out/
COPY --from=build --chown=bank:bank /build/lib/ /app/lib/
ENV PORT=10000 BANK_HOST=0.0.0.0 BANK_DB_PATH=/var/data/bank.db BANK_DATA_FILE=/var/data/bank.dat
USER bank
EXPOSE 10000
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-XX:MaxRAMPercentage=70", "-Dbank.keyDirectory=/var/data/keys", "-cp", "/app/out:/app/lib/*", "bank.BankApplication"]
