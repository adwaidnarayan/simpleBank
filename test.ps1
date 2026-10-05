$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
New-Item -ItemType Directory -Force out | Out-Null
javac -encoding UTF-8 -d out src/bank/*.java tests/bank/*.java
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed.' }
java -cp out bank.BankSystemTest
if ($LASTEXITCODE -ne 0) { throw 'Tests failed.' }
java -cp out bank.WebsiteTest
if ($LASTEXITCODE -ne 0) { throw 'Website tests failed.' }
java -cp out bank.EncryptionTest
if ($LASTEXITCODE -ne 0) { throw 'Encryption tests failed.' }
