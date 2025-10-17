# GuoZhan v1.3.19 Build Script
# PowerShell script to build the project

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "GuoZhan v1.3.19 Build Script" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# Set Java environment
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

Write-Host ""
Write-Host "[1/4] Verifying Java environment..." -ForegroundColor Yellow
& java -version
if ($LASTEXITCODE -ne 0) {
    Write-Host "Error: Java environment configuration failed" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "[2/4] Cleaning old build files..." -ForegroundColor Yellow
& .\gradlew.bat clean --no-daemon --console=plain
if ($LASTEXITCODE -ne 0) {
    Write-Host "Error: Clean failed" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "[3/4] Compiling Kotlin code..." -ForegroundColor Yellow
& .\gradlew.bat compileKotlin --no-daemon --console=plain
if ($LASTEXITCODE -ne 0) {
    Write-Host "Error: Compilation failed" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "[4/4] Generating JAR file..." -ForegroundColor Yellow
& .\gradlew.bat shadowJar --no-daemon --console=plain
if ($LASTEXITCODE -ne 0) {
    Write-Host "Error: JAR generation failed" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Green
Write-Host "Build Complete!" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green

Write-Host ""
Write-Host "Verifying build results..." -ForegroundColor Yellow
if (Test-Path "build\libs\Guozhan-1.0-SNAPSHOT.jar") {
    Write-Host "✓ JAR file generated successfully" -ForegroundColor Green
    Get-Item "build\libs\Guozhan-1.0-SNAPSHOT.jar" | Format-List Name, Length, LastWriteTime
} else {
    Write-Host "✗ JAR file not found" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Green
Write-Host "Build successful!" -ForegroundColor Green
Write-Host "JAR location: build\libs\Guozhan-1.0-SNAPSHOT.jar" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green

