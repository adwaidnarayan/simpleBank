$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
& "$PSScriptRoot/dependencies.ps1"
New-Item -ItemType Directory -Force out | Out-Null
javac -encoding UTF-8 -d out src/bank/*.java
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed.' }
java --enable-native-access=ALL-UNNAMED -cp 'out;lib/*' bank.BankApplication @args
