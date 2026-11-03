<#
    Launch the backend for local work against a disposable, loopback-only database that this
    script creates and destroys.

    WHY THIS EXISTS
      `mvn spring-boot:run -Dspring-boot.run.profiles=test` does NOT isolate anything. Two
      separate reasons, both of which bit on 2026-11-02:

        1. application-test.properties lives in src/test/resources, which is not on the
           classpath spring-boot:run uses. The profile activates with nothing behind it, so
           src/main/resources/application.properties - the production datasource - stays in
           force.
        2. The h2 dependency is <scope>test</scope>, so H2 is not on the spring-boot:run
           classpath at all. Even correct properties could not have produced an embedded
           database; Spring would have fallen back or failed.

      Together those connected a development startup to the production database and let
      ddl-auto=update alter its schema. See docs/closeout-version-incident.md.

    WHAT THIS DOES DIFFERENTLY
      - Creates its own disposable MySQL container, pinned by digest, published on loopback
        only, with credentials generated per run. Same engine as production, none of its data.
      - Passes spring.datasource.* explicitly. No profile is activated, so there is no
        properties file whose absence can silently fall back to production.
      - Refuses to launch unless the resolved JDBC URL names a loopback host and the port
        matches the container this script just created.
      - Blanks every production and messaging variable in the child process, so nothing can
        be inherited from the operator's environment.
      - Verifies from the RUNNING process's own log that the URL really is the loopback one,
        and aborts if any remote-host pattern appears.
      - Removes the container and network on exit, and only ever touches resources it
        created and labelled itself.
      - Never reads, writes or echoes application.properties.

    USAGE
      .\scripts\run-local-isolated.ps1              # start; prints the port when confirmed
      .\scripts\run-local-isolated.ps1 -Stop        # stop the app and destroy the database

    Nothing persists: the database lives in a tmpfs inside a container that is removed.
#>
[CmdletBinding()]
param(
    [ValidateRange(1024, 65535)]
    [int]$Port = 8099,

    [switch]$Stop,

    [ValidateRange(10, 900)]
    [int]$ReadyTimeoutSeconds = 300
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false

$projectRoot = Split-Path -Parent $PSScriptRoot
$runDir = Join-Path $projectRoot 'target/local-isolated'
$stateFile = Join-Path $runDir 'state.json'
$logFile = Join-Path $runDir 'local-isolated.log'
$errFile = Join-Path $runDir 'local-isolated.err.log'

$image = 'mysql:9.7.2@sha256:e2fa358a74ac81148e7e27d9433e4c0ab62133de8ea4428b4f39a51a4ba7d07f'
$labelKey = 'com.intfitout.local.isolated'
$database = 'intfitout_local_smoke'

# Any of these appearing in a resolved URL means we are not where we think we are.
$remotePatterns = @('rlwy', 'railway', 'amazonaws', 'azure', 'rds\.', 'proxy\.', '\.net', '\.com', '\.io')

<#
    Maven's spring-boot:run FORKS a separate JVM. Killing mvn.cmd leaves that JVM running,
    still holding the port and still connected to whatever database it opened. An orphan
    exactly like that is what compounded the 2026-11-02 incident, so every stop path here
    goes through Stop-Tracked, and the forked child is identified by this run's own unique
    database name before anything is killed.
#>
function Find-ForkedJava([string]$Marker, [int]$TimeoutSeconds = 120) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $hit = Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
            Where-Object { $_.CommandLine -like ("*" + $Marker + "*") } | Select-Object -First 1
        if ($hit) { return [int]$hit.ProcessId }
        Start-Sleep -Milliseconds 500
    }
    return 0
}

function Stop-OneIfOurs([int]$ProcessId, [string]$Marker) {
    if ($ProcessId -le 0) { return }
    $proc = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if (-not $proc) { return }
    $cl = (Get-CimInstance Win32_Process -Filter "ProcessId = $ProcessId" -ErrorAction SilentlyContinue).CommandLine
    # Never kill a pid that has been recycled onto something of the operator's.
    if ($cl -and $cl -like ("*" + $Marker + "*")) {
        Stop-Process -Id $ProcessId -Force -ErrorAction SilentlyContinue
        Write-Output ("  stopped pid $ProcessId")
    } else {
        Write-Output ("  pid $ProcessId is not ours any more; left alone")
    }
}

function Assert-LocalDocker {
    $endpoint = & docker context inspect desktop-linux --format '{{.Endpoints.docker.Host}}'
    if ($LASTEXITCODE -ne 0 -or $endpoint -ne 'npipe:////./pipe/dockerDesktopLinuxEngine') {
        throw 'REFUSING: this launcher requires the local Windows Docker Desktop Linux engine; remote Docker is forbidden.'
    }
    $serverOs = & docker --context desktop-linux version --format '{{.Server.Os}}'
    if ($LASTEXITCODE -ne 0 -or $serverOs -ne 'linux') {
        throw 'REFUSING: local Docker Linux engine unavailable. There is no database fallback.'
    }
}

# --------------------------------------------------------------------------- stop mode

function Stop-Launched {
    if (-not (Test-Path -LiteralPath $stateFile)) {
        Write-Output 'No state file; nothing started by this script is recorded.'
        return
    }
    $state = Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json

    $marker = if ($state.marker) { $state.marker } else { $database }
    Write-Output 'Stopping tracked processes...'
    # The forked JVM first: it is the one holding the port and the database connection.
    if ($state.javaPid) { Stop-OneIfOurs ([int]$state.javaPid) $marker }
    if ($state.pid) { Stop-OneIfOurs ([int]$state.pid) 'spring-boot:run' }
    # Sweep for any other JVM carrying this run's unique marker.
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like ("*" + $marker + "*") } |
        ForEach-Object { Stop-OneIfOurs ([int]$_.ProcessId) $marker }

    # Remove only resources carrying this run's own label.
    if ($state.container) {
        $owned = & docker --context desktop-linux ps -aq --filter ("label=" + $labelKey + "=" + $state.runId)
        if ($LASTEXITCODE -eq 0 -and $owned) {
            & docker --context desktop-linux rm --force --volumes $state.container | Out-Null
            Write-Output 'Removed this run''s disposable MySQL container and anonymous volumes.'
        } else {
            Write-Output 'Container not found by this run''s label; removing nothing.'
        }
    }
    if ($state.network) {
        & docker --context desktop-linux network rm $state.network 2>$null | Out-Null
        Write-Output 'Removed this run''s dedicated Docker network.'
    }
    Remove-Item -LiteralPath $stateFile -Force -ErrorAction SilentlyContinue
}

if ($Stop) { Stop-Launched; return }

# --------------------------------------------------------------------------- preflight

if (Test-Path -LiteralPath $stateFile) {
    $prev = Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json
    if ($prev.pid -match '^\d+$' -and (Get-Process -Id ([int]$prev.pid) -ErrorAction SilentlyContinue)) {
        throw ("REFUSING TO LAUNCH: pid " + $prev.pid + " from a previous run is still alive. " +
            'Run with -Stop first. An orphan holding the port is how the last incident compounded.')
    }
    Write-Output 'Clearing a stale state file from a previous run.'
    Stop-Launched
}

$inUse = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if ($inUse) {
    throw ("REFUSING TO LAUNCH: port $Port is already in use by pid " +
        ($inUse | Select-Object -First 1).OwningProcess + '. Choose another -Port or stop that process.')
}

Assert-LocalDocker
$null = Get-Command 'mvn.cmd' -ErrorAction Stop
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
foreach ($f in @($logFile, $errFile)) { if (Test-Path -LiteralPath $f) { Remove-Item -LiteralPath $f -Force } }

$runId = [Guid]::NewGuid().ToString('N')
$containerName = 'intfitout-local-isolated-' + $runId
$networkName = $containerName + '-net'
$dbUser = 'li_' + $runId.Substring(0, 12)
$dbPassword = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
$rootPassword = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')

$containerCreated = $false
$networkCreated = $false

function Invoke-OwnedSql([string]$Sql) {
    & docker --context desktop-linux exec $containerName sh -c `
        'export MYSQL_PWD="$MYSQL_PASSWORD"; exec mysql --protocol=tcp --host=127.0.0.1 --user="$MYSQL_USER" --database="$MYSQL_DATABASE" --batch --skip-column-names --execute="$1"' sh $Sql
}

try {
    $env:MYSQL_ROOT_PASSWORD = $rootPassword
    $env:MYSQL_DATABASE = $database
    $env:MYSQL_USER = $dbUser
    $env:MYSQL_PASSWORD = $dbPassword

    & docker --context desktop-linux image inspect $image --format '{{.Id}}' | Out-Null
    if ($LASTEXITCODE -ne 0) {
        & docker --context desktop-linux pull $image
        if ($LASTEXITCODE -ne 0) { throw 'Could not obtain the pinned official MySQL image.' }
    }

    & docker --context desktop-linux network create --driver bridge --label ($labelKey + '=' + $runId) $networkName | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not create a fresh dedicated Docker network.' }
    $networkCreated = $true

    # --publish 127.0.0.1:: binds a random high port on LOOPBACK ONLY. Nothing off-box
    # can reach this database, and the tmpfs means it never touches disk.
    & docker --context desktop-linux create --pull=never --name $containerName `
        --label ($labelKey + '=' + $runId) --network $networkName `
        --publish '127.0.0.1::3306' --memory 1g --cpus 2 `
        --tmpfs '/var/lib/mysql:rw,nosuid,size=536870912' `
        --env MYSQL_ROOT_PASSWORD --env MYSQL_DATABASE --env MYSQL_USER --env MYSQL_PASSWORD `
        $image --character-set-server=utf8mb4 --collation-server=utf8mb4_0900_ai_ci | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the disposable MySQL container.' }
    $containerCreated = $true

    & docker --context desktop-linux start $containerName | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not start the disposable MySQL container.' }
    Write-Output ('Created disposable container: ' + $containerName)

    $ready = $false
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        $probe = Invoke-OwnedSql 'SELECT 1' 2>$null
        if ($LASTEXITCODE -eq 0 -and $probe -eq '1') { $ready = $true; break }
        Start-Sleep -Seconds 2
    }
    if (-not $ready) { throw 'Disposable MySQL did not become ready. No other database was contacted.' }

    $binding = & docker --context desktop-linux port $containerName 3306/tcp
    if ($LASTEXITCODE -ne 0 -or -not $binding) { throw 'Could not read the published loopback port.' }
    $bindingLine = ($binding | Select-Object -First 1).Trim()
    if ($bindingLine -notmatch '^127\.0\.0\.1:(\d{2,5})$') {
        throw ("REFUSING: published binding '$bindingLine' is not loopback-only.")
    }
    $dbPort = [int]$Matches[1]

    $jdbcUrl = "jdbc:mysql://127.0.0.1:$dbPort/$database" +
        '?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'

    # Guard the URL we are about to hand the application.
    if ($jdbcUrl -notmatch '^jdbc:mysql://127\.0\.0\.1:\d+/') {
        throw 'REFUSING: resolved JDBC URL is not loopback.'
    }
    foreach ($pattern in $remotePatterns) {
        if ($jdbcUrl -match $pattern) { throw ("REFUSING: JDBC URL matches remote pattern '$pattern'.") }
    }

    # ----------------------------------------------------------------- child environment

    # Blanked in the CHILD only; the operator's session is untouched. Spring maps
    # SPRING_DATASOURCE_* onto spring.datasource.*, so an inherited value could override
    # what we pass. The WhatsApp variables are blanked so nothing can be sent and no daily
    # report scheduled, whatever the operator has configured.
    $blankNames = @(
        'SPRING_DATASOURCE_URL', 'SPRING_DATASOURCE_USERNAME', 'SPRING_DATASOURCE_PASSWORD',
        'SPRING_DATASOURCE_DRIVER_CLASS_NAME', 'SPRING_PROFILES_ACTIVE', 'SPRING_APPLICATION_JSON',
        'DATABASE_URL', 'MYSQL_URL', 'MYSQLHOST', 'MYSQLPORT', 'MYSQLUSER', 'MYSQLPASSWORD',
        'WHATSAPP_ACCESS_TOKEN', 'WHATSAPP_GRAPH_API_VERSION', 'WHATSAPP_REPORTING_ADMIN_SECRET',
        'WHATSAPP_REPORTING_DAILY_ENABLED', 'WHATSAPP_REPORTING_ENABLED',
        'WHATSAPP_REPORTING_MANAGEMENT_FATHER_RECIPIENT', 'WHATSAPP_REPORTING_MANAGEMENT_OWNER_RECIPIENT',
        'WHATSAPP_REPORTING_MANAGEMENT_TEMPLATE_CONFIRMED', 'WHATSAPP_REPORTING_MANAGEMENT_TEMPLATE_LANGUAGE',
        'WHATSAPP_REPORTING_PHONE_NUMBER_ID', 'WHATSAPP_REPORTING_PILOT_RECIPIENT',
        'WHATSAPP_REPORTING_PILOT_WORKER_ID', 'WHATSAPP_REPORTING_ROLLOUT_ENABLED',
        'WHATSAPP_REPORTING_SEND_ENABLED', 'WHATSAPP_REPORTING_TEMPLATE_CONFIRMED',
        'WHATSAPP_REPORTING_TEMPLATE_LANGUAGE', 'WHATSAPP_SCHEDULED_TEST_ENABLED'
    )
    $saved = @{}
    foreach ($name in $blankNames) {
        $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        # $null removes the variable outright. An empty string can be read as
        # "present but blank", which for SPRING_DATASOURCE_URL would override the explicit
        # -D and leave the application with no URL at all.
        [Environment]::SetEnvironmentVariable($name, $null, 'Process')
    }

    # No profile is activated at all: nothing can fall back to a missing properties file.
    $jvmArgs = @(
        "-Dserver.port=$Port"
        "-Dspring.datasource.url=$jdbcUrl"
        '-Dspring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver'
        "-Dspring.datasource.username=$dbUser"
        "-Dspring.datasource.password=$dbPassword"
        '-Dspring.jpa.hibernate.ddl-auto=update'
        '-Dspring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect'
        '-Dspring.jpa.show-sql=true'
        # Hikari prints its resolved configuration, jdbcUrl included, at DEBUG. Without this
        # the running application never states which database it opened, and the isolation
        # claim could only be inferred - which is exactly how the last incident went unseen.
        '-Dlogging.level.com.zaxxer.hikari.HikariConfig=DEBUG'
        '-Dspring.datasource.hikari.pool-name=local-isolated-pool'
    ) -join ' '

    Write-Output 'Launching isolated backend'
    Write-Output ("  app port    : $Port")
    Write-Output ("  database    : 127.0.0.1:$dbPort/$database  (disposable container, loopback only)")
    Write-Output ("  schema      : ddl-auto=update on an empty throwaway database")
    Write-Output ("  messaging   : blanked in the child process")
    Write-Output ("  log         : $logFile")
    Write-Output ''

    try {
        # The value contains spaces, so it must arrive as ONE quoted argument. Without the
        # embedded quotes Maven takes only the first token and every -D silently vanishes,
        # which presents as "'url' attribute is not specified".
        $quotedJvmArgs = '"-Dspring-boot.run.jvmArguments=' + $jvmArgs + '"'
        $process = Start-Process -FilePath 'mvn.cmd' `
            -ArgumentList @('-o', '-ntp', 'spring-boot:run', $quotedJvmArgs) `
            -WorkingDirectory $projectRoot `
            -RedirectStandardOutput $logFile -RedirectStandardError $errFile `
            -PassThru -NoNewWindow
    } finally {
        foreach ($name in $blankNames) {
            [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process')
        }
    }

    # This run's unique marker: the database name carries the per-run port, so no other
    # process on the machine can match it.
    $marker = "$database" + '?useSSL'
    $markerSimple = "127.0.0.1:$dbPort/$database"

    $javaPid = Find-ForkedJava $markerSimple 180
    @{ pid = $process.Id; javaPid = $javaPid; container = $containerName; network = $networkName
       runId = $runId; dbPort = $dbPort; appPort = $Port; marker = $markerSimple } |
        ConvertTo-Json | Set-Content -LiteralPath $stateFile

    if ($javaPid -eq 0) {
        Write-Output 'WARNING: could not identify the forked JVM; -Stop will sweep by marker instead.'
    } else {
        Write-Output ("Forked JVM pid $javaPid tracked (the process that actually holds the port).")
    }

    # ----------------------------------------------------------------- verify the running app

    Write-Output 'Waiting for the application to report its datasource...'
    $deadline = (Get-Date).AddSeconds($ReadyTimeoutSeconds)
    $started = $false
    $remoteSeen = $null

    while ((Get-Date) -lt $deadline) {
        if (Test-Path -LiteralPath $logFile) {
            $text = Get-Content -LiteralPath $logFile -Raw -ErrorAction SilentlyContinue
            if ($text) {
                # The failure actually being guarded against, checked every pass.
                foreach ($pattern in $remotePatterns) {
                    if ($text -match ('jdbc:mysql://[^\s]*' + $pattern)) { $remoteSeen = $pattern; break }
                }
                if ($remoteSeen) { break }
                if ($text -match 'Started IntFitBackendApplication') { $started = $true; break }
                if ($text -match 'APPLICATION FAILED TO START|BUILD FAILURE') { break }
            }
        }
        if ($process.HasExited) { break }
        Start-Sleep -Milliseconds 700
    }

    if ($remoteSeen) {
        Stop-OneIfOurs $javaPid $markerSimple
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        throw ("ABORTED: the running process referenced a remote host matching '$remoteSeen'. " +
            'It has been stopped. Do not retry until you know why the explicit override did not apply.')
    }
    if (-not $started) {
        Stop-OneIfOurs $javaPid $markerSimple
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        throw ("Application did not report readiness; stopped. See $logFile")
    }

    # Readiness is not enough: confirm positively, from the running process's own log, that
    # the connection is the loopback one this script created.
    # Positive confirmation from the application's own resolved Hikari configuration, not
    # from a properties file and not by inference.
    $log = Get-Content -LiteralPath $logFile -Raw
    $confirmed = $log -match ('jdbcUrl\.*127\.0\.0\.1:' + $dbPort + '/' + [regex]::Escape($database))
    if (-not $confirmed) {
        $confirmed = $log -match [regex]::Escape("127.0.0.1:$dbPort/$database")
    }
    if (-not $confirmed) {
        Stop-OneIfOurs $javaPid $markerSimple
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        throw ('ABORTED: the running application never stated it opened 127.0.0.1:' + $dbPort +
            '. Stopped rather than assume isolation. See ' + $logFile)
    }
    # And no remote datasource may appear anywhere in the log.
    foreach ($pattern in $remotePatterns) {
        if ($log -match ('jdbcUrl[^

]*' + $pattern)) {
            Stop-OneIfOurs $javaPid $markerSimple
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
            throw ("ABORTED: a remote jdbcUrl matching '$pattern' appears in the log. Stopped.")
        }
    }

    Write-Output ''
    Write-Output ("CONFIRMED isolated. pid $($process.Id), app on $Port, db on 127.0.0.1:$dbPort.")
    foreach ($needle in @("127.0.0.1:$dbPort/$database", 'Started IntFitBackendApplication',
                          'outbox_processor', 'whatsapp_scheduled_test', 'Material name cleaner')) {
        $hit = Select-String -LiteralPath $logFile -Pattern $needle -SimpleMatch | Select-Object -First 1
        if ($hit) { Write-Output ('  ' + $hit.Line.Trim()) }
    }
    Write-Output ''
    Write-Output 'Stop and destroy with: .\scripts\run-local-isolated.ps1 -Stop'
}
catch {
    # Never leave the forked JVM, a container or a network behind on a failed launch.
    if ($markerSimple) {
        Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
            Where-Object { $_.CommandLine -like ("*" + $markerSimple + "*") } |
            ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    }
    if ($containerCreated) { & docker --context desktop-linux rm --force --volumes $containerName 2>$null | Out-Null }
    if ($networkCreated) { & docker --context desktop-linux network rm $networkName 2>$null | Out-Null }
    Remove-Item -LiteralPath $stateFile -Force -ErrorAction SilentlyContinue
    throw
}
finally {
    foreach ($name in @('MYSQL_ROOT_PASSWORD', 'MYSQL_DATABASE', 'MYSQL_USER', 'MYSQL_PASSWORD')) {
        [Environment]::SetEnvironmentVariable($name, $null, 'Process')
    }
}
