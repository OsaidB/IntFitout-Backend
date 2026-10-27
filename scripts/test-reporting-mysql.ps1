[CmdletBinding()]
param(
    [string]$MavenExecutable = 'mvn.cmd',
    # Optional method selection within the same guarded integration class; no arbitrary suites.
    [ValidatePattern('^[A-Za-z][A-Za-z0-9]*(\+[A-Za-z][A-Za-z0-9]*)*$')]
    [string]$IntegrationMethods
)

# Only this script's freshly created local Docker resources may be used or removed.
# No application properties, existing containers/volumes, or external database URLs are read.
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$projectRoot = Split-Path -Parent $PSScriptRoot
$runId = [Guid]::NewGuid().ToString('N')
$containerName = 'intfitout-reporting-test-' + $runId
$networkName = $containerName + '-net'
$image = 'mysql:9.7.2@sha256:e2fa358a74ac81148e7e27d9433e4c0ab62133de8ea4428b4f39a51a4ba7d07f'
$labelKey = 'com.intfitout.reporting.validation'
$containerCreated = $false
$networkCreated = $false
$testExit = 1
$configurationPath = Join-Path $projectRoot 'src/main/resources/application.properties'
$configurationHash = (Get-FileHash -LiteralPath $configurationPath -Algorithm SHA256).Hash
$variableNames = @('MYSQL_ROOT_PASSWORD','MYSQL_DATABASE','MYSQL_USER','MYSQL_PASSWORD',
    'INTFITOUT_MYSQL_TEST_ENABLED','INTFITOUT_MYSQL_TEST_PORT','INTFITOUT_MYSQL_TEST_USER',
    'INTFITOUT_MYSQL_TEST_PASSWORD','INTFITOUT_MYSQL_TEST_RUN_ID','MAVEN_SKIP_RC')
$previousEnvironment = @{}
foreach ($name in $variableNames) { $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }

function Assert-LocalDocker {
    $endpoint = & docker context inspect desktop-linux --format '{{.Endpoints.docker.Host}}'
    if ($LASTEXITCODE -ne 0 -or $endpoint -ne 'npipe:////./pipe/dockerDesktopLinuxEngine') {
        throw 'This runner requires the local Windows Docker Desktop Linux engine; remote Docker is forbidden.'
    }
    $serverOs = & docker --context desktop-linux version --format '{{.Server.Os}}'
    if ($LASTEXITCODE -ne 0 -or $serverOs -ne 'linux') { throw 'Local Docker Linux engine unavailable. No database fallback is permitted.' }
}

function Invoke-TestSql([string]$Sql) {
    # Read the generated credential only inside our new container; no password on CLI or in output.
    & docker --context desktop-linux exec $containerName sh -c `
        'export MYSQL_PWD="$MYSQL_PASSWORD"; exec mysql --protocol=tcp --host=127.0.0.1 --user="$MYSQL_USER" --database="$MYSQL_DATABASE" --batch --skip-column-names --execute="$1"' sh $Sql
}

try {
    Assert-LocalDocker
    $null = Get-Command $MavenExecutable -ErrorAction Stop
    $null = New-Item -ItemType Directory -Force -Path (Join-Path $projectRoot 'target')
    $env:MYSQL_ROOT_PASSWORD = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
    $env:MYSQL_DATABASE = 'intfitout_reporting_validation'
    $env:MYSQL_USER = 'rv_' + $runId.Substring(0, 12)
    $env:MYSQL_PASSWORD = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')

    & docker --context desktop-linux image inspect $image --format '{{.Id}}' | Out-Null
    if ($LASTEXITCODE -ne 0) {
        & docker --context desktop-linux pull $image
        if ($LASTEXITCODE -ne 0) { throw 'Could not obtain the pinned official MySQL test image.' }
    }
    # A dedicated bridge permits a loopback-only published port on Docker Desktop.
    # No existing container joins it. Tests hardcode the guarded loopback DB; Meta is stubbed.
    & docker --context desktop-linux network create --driver bridge --label ($labelKey + '=' + $runId) $networkName | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not create a fresh dedicated Docker network.' }
    $networkCreated = $true
    & docker --context desktop-linux create --pull=never --name $containerName `
        --label ($labelKey + '=' + $runId) --network $networkName `
        --publish '127.0.0.1::3306' --memory 1g --cpus 2 `
        --tmpfs '/var/lib/mysql:rw,nosuid,size=536870912' `
        --env MYSQL_ROOT_PASSWORD --env MYSQL_DATABASE --env MYSQL_USER --env MYSQL_PASSWORD `
        $image --character-set-server=utf8mb4 --collation-server=utf8mb4_0900_ai_ci | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the new disposable MySQL container.' }
    $containerCreated = $true
    & docker --context desktop-linux start $containerName | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not start the owned disposable MySQL container.' }
    Write-Output ('Created isolated disposable container: ' + $containerName)

    $ready = $false
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        $readyResult = Invoke-TestSql 'SELECT 1' 2>$null
        if ($LASTEXITCODE -eq 0 -and $readyResult -eq '1') { $ready = $true; break }
        Start-Sleep -Seconds 2
    }
    if (-not $ready) { throw 'Disposable MySQL did not become ready. No other database was contacted.' }

    $binding = & docker --context desktop-linux port $containerName 3306/tcp
    if ($LASTEXITCODE -ne 0 -or $binding -notmatch '^127\.0\.0\.1:([0-9]+)$') { throw 'Container port must bind exclusively to IPv4 loopback.' }
    $env:INTFITOUT_MYSQL_TEST_PORT = $Matches[1]
    $guardSql = "CREATE TABLE intfitout_validation_guard (run_id CHAR(32) PRIMARY KEY); INSERT INTO intfitout_validation_guard VALUES ('$runId'); SELECT VERSION();"
    $mysqlVersion = Invoke-TestSql $guardSql
    if ($LASTEXITCODE -ne 0) { throw 'Could not install the per-run guard in the new test database.' }
    Write-Output ('Actual disposable MySQL version: ' + $mysqlVersion)
    if ($mysqlVersion -ne '9.7.2') { throw 'Unexpected disposable MySQL version; expected exactly 9.7.2. Tests were not started.' }
    Write-Output ('Pinned image: ' + $image)
    $env:INTFITOUT_MYSQL_TEST_ENABLED = 'true'
    $env:INTFITOUT_MYSQL_TEST_USER = $env:MYSQL_USER
    $env:INTFITOUT_MYSQL_TEST_PASSWORD = $env:MYSQL_PASSWORD
    $env:INTFITOUT_MYSQL_TEST_RUN_ID = $runId
    $env:MAVEN_SKIP_RC = '1'
    # Both guarded MySQL suites run against the same disposable container.
    $suites = 'ReportingMySqlIntegrationTest'
    if ($IntegrationMethods) { $suites += '#' + $IntegrationMethods }
    if (-not $IntegrationMethods) { $suites += ',SupplierPaymentMySqlIntegrationTest' }
    Push-Location -LiteralPath $projectRoot
    try {
        $logPath = Join-Path $projectRoot 'target/reporting-mysql-validation.log'
        & $MavenExecutable -o -B -ntp ('-Dtest=' + $suites) test *> $logPath
        $testExit = $LASTEXITCODE
        Select-String -LiteralPath $logPath -Pattern 'Tests run:|BUILD SUCCESS|BUILD FAILURE|\[ERROR\]|reporting_mysql_version=' | ForEach-Object { $_.Line }
        Write-Output 'Detailed local test output: target/reporting-mysql-validation.log (synthetic fixtures only).'
    } finally { Pop-Location }
} finally {
    # Verify exact ownership before removing either resource; never enumerate/delete other containers.
    $cleanupErrors = @()
    if ($containerCreated) {
        $owner = & docker --context desktop-linux inspect --format ('{{index .Config.Labels "' + $labelKey + '"}}') $containerName
        if ($LASTEXITCODE -eq 0 -and $owner -eq $runId) {
            & docker --context desktop-linux rm --force --volumes $containerName | Out-Null
            if ($LASTEXITCODE -ne 0) { $cleanupErrors += ('Failed to remove owned container: ' + $containerName) }
            else { Write-Output 'Removed this run''s disposable MySQL container and anonymous volumes.' }
        } else { $cleanupErrors += ('Container ownership check failed; refusing cleanup: ' + $containerName) }
    }
    if ($networkCreated) {
        $owner = & docker --context desktop-linux network inspect --format ('{{index .Labels "' + $labelKey + '"}}') $networkName
        if ($LASTEXITCODE -eq 0 -and $owner -eq $runId) {
            & docker --context desktop-linux network rm $networkName | Out-Null
            if ($LASTEXITCODE -ne 0) { $cleanupErrors += ('Failed to remove owned network: ' + $networkName) }
            else { Write-Output 'Removed this run''s dedicated Docker network.' }
        } else { $cleanupErrors += ('Network ownership check failed; refusing cleanup: ' + $networkName) }
    }
    foreach ($name in $variableNames) { [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process') }
    if ((Get-FileHash -LiteralPath $configurationPath -Algorithm SHA256).Hash -ne $configurationHash) {
        $cleanupErrors += 'application.properties changed during the validation run.'
    }
    if ($cleanupErrors.Count -gt 0) { throw ($cleanupErrors -join '; ') }
}
exit $testExit
