$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
New-Item -ItemType Directory -Force out | Out-Null
javac -encoding UTF-8 -d out src/bank/*.java
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed.' }
java -cp out bank.BankApplication @args
