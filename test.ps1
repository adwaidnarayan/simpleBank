$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
& "$PSScriptRoot/dependencies.ps1"
New-Item -ItemType Directory -Force out | Out-Null
javac -encoding UTF-8 -d out src/bank/*.java tests/bank/*.java
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed.' }
java --enable-native-access=ALL-UNNAMED -cp "out;lib/*" bank.BankSystemTest
if ($LASTEXITCODE -ne 0) { throw 'Tests failed.' }
java --enable-native-access=ALL-UNNAMED -cp "out;lib/*" bank.WebsiteTest
if ($LASTEXITCODE -ne 0) { throw 'Website tests failed.' }
java --enable-native-access=ALL-UNNAMED -cp "out;lib/*" bank.EncryptionTest
if ($LASTEXITCODE -ne 0) { throw 'Encryption tests failed.' }
java --enable-native-access=ALL-UNNAMED -cp 'out;lib/*' bank.SqliteTest
if ($LASTEXITCODE -ne 0) { throw 'SQLite tests failed.' }
