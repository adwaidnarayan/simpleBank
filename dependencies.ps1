$ErrorActionPreference = 'Stop'
$dependencyDir = Join-Path $PSScriptRoot 'lib'
New-Item -ItemType Directory -Force $dependencyDir | Out-Null
$artifact = 'sqlite-jdbc-3.53.4.0.jar'
$url = 'https://repo.maven.apache.org/maven2/org/xerial/sqlite-jdbc/3.53.4.0/' + $artifact
$target = Join-Path $dependencyDir $artifact
if (!(Test-Path -LiteralPath $target)) {
    $expected = (Invoke-RestMethod ($url + '.sha256')).Trim()
    $temporary = $target + '.download'
    Invoke-WebRequest $url -OutFile $temporary
    if ((Get-FileHash $temporary -Algorithm SHA256).Hash -ne $expected) { throw 'SQLite download checksum mismatch.' }
    Move-Item -LiteralPath $temporary -Destination $target
}
