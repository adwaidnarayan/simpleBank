@echo off
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0dependencies.ps1"
if errorlevel 1 exit /b 1
if not exist out mkdir out
javac -encoding UTF-8 -d out src\bank\*.java
if errorlevel 1 exit /b 1
java --enable-native-access=ALL-UNNAMED -cp "out;lib/*" bank.BankApplication %*
