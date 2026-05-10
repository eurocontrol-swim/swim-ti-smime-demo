# Setup ActiveMQ Artemis for SWIM S/MIME Demo
# This script downloads (if needed) and configures an Artemis broker instance.

param(
    [string]$ArtemisVersion = "2.37.0",
    [string]$InstallDir = "$PSScriptRoot\artemis",
    [string]$InstanceName = "swim-broker"
)

$ErrorActionPreference = "Stop"

$ArtemisHome = "$InstallDir\apache-artemis-$ArtemisVersion"
$InstanceDir = "$InstallDir\$InstanceName"

# Download Artemis if not present
if (-not (Test-Path "$ArtemisHome\bin\artemis.cmd")) {
    Write-Host "Downloading Apache ActiveMQ Artemis $ArtemisVersion..."
    $url = "https://archive.apache.org/dist/activemq/activemq-artemis/$ArtemisVersion/apache-artemis-$ArtemisVersion-bin.zip"
    $zipFile = "$InstallDir\artemis.zip"

    New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
    Invoke-WebRequest -Uri $url -OutFile $zipFile
    Write-Host "Extracting..."
    Expand-Archive -Path $zipFile -DestinationPath $InstallDir -Force
    Remove-Item $zipFile
    Write-Host "Artemis installed at: $ArtemisHome"
} else {
    Write-Host "Artemis already installed at: $ArtemisHome"
}

# Create broker instance if not present
if (-not (Test-Path "$InstanceDir\bin\artemis.cmd")) {
    Write-Host "Creating broker instance: $InstanceName..."
    & "$ArtemisHome\bin\artemis.cmd" create $InstanceDir `
        --user admin --password admin `
        --allow-anonymous `
        --no-amqp-acceptor `
        --no-hornetq-acceptor `
        --no-mqtt-acceptor `
        --no-stomp-acceptor `
        --queues "swim.flight.data" `
        --require-login

    # The default acceptor already supports AMQP on port 61616
    # Add a dedicated AMQP acceptor on port 5672
    $brokerXml = "$InstanceDir\etc\broker.xml"
    $content = Get-Content $brokerXml -Raw
    $amqpAcceptor = '         <acceptor name="amqp">tcp://0.0.0.0:5672?protocols=AMQP</acceptor>'
    $content = $content -replace '(</acceptors>)', "$amqpAcceptor`n`$1"
    Set-Content -Path $brokerXml -Value $content

    Write-Host "Broker instance created at: $InstanceDir"
} else {
    Write-Host "Broker instance already exists at: $InstanceDir"
}

Write-Host ""
Write-Host "=== Artemis Setup Complete ==="
Write-Host "To start the broker:"
Write-Host "  $InstanceDir\bin\artemis.cmd run"
Write-Host ""
Write-Host "AMQP endpoint: amqp://localhost:5672"
Write-Host "Web console:   http://localhost:8161/console"
Write-Host "Credentials:   admin / admin"
Write-Host "Queue:         swim.flight.data"
