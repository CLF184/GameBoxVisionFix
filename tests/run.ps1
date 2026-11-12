param([string]$JdkPath = 'D:\Program Files\Java\jdk-25.0.4.101-hotspot')
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path $PSScriptRoot -Parent)
New-Item -ItemType Directory -Force -Path build/tests | Out-Null
$classpath = 'tools/android-35.jar;tools/libxposed-api-102.jar;tools/dexkit/classes.jar;tools/kotlin-stdlib-1.5.0.jar;tools/flatbuffers-java-23.5.26.jar'
$sources = @(Get-ChildItem tests,src/main/java -Recurse -Filter *.java | ForEach-Object FullName)
& "$JdkPath/bin/javac.exe" --release 17 -encoding UTF-8 -cp $classpath -d build/tests @sources
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
& "$JdkPath/bin/java.exe" -cp "build/tests;$classpath" RecoveryTest
if ($LASTEXITCODE -ne 0) { throw 'Recovery regression failed' }
