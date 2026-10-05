FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /build
COPY src/ src/
RUN mkdir out && javac --release 17 -encoding UTF-8 -d out src/bank/*.java

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN groupadd --gid 10001 bank && useradd --uid 10001 --gid bank --create-home bank \
    && mkdir -p /var/data && chown bank:bank /var/data
COPY --from=build --chown=bank:bank /build/out/ /app/out/
ENV PORT=10000 BANK_HOST=0.0.0.0 BANK_DATA_FILE=/var/data/bank.dat
USER bank
EXPOSE 10000
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-Dbank.keyDirectory=/var/data/keys", "-cp", "/app/out", "bank.BankApplication"]
