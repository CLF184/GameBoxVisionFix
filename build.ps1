param(
    [string]$JdkPath = 'D:\Program Files\Java\jdk-25.0.4.101-hotspot'
)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Program failed with exit code $LASTEXITCODE"
    }
}

$java = Join-Path $JdkPath 'bin/java.exe'
$javac = Join-Path $JdkPath 'bin/javac.exe'
$jar = Join-Path $JdkPath 'bin/jar.exe'
$keytool = Join-Path $JdkPath 'bin/keytool.exe'

if (!(Test-Path -LiteralPath $java)) {
    throw "JDK not found: $JdkPath"
}

New-Item -ItemType Directory -Force -Path build/classes,build/dex,outputs | Out-Null
Invoke-Checked 'python' @('tools/prepare_dependencies.py')

$dependencies = @(
    'tools/dexkit/classes.jar',
    'tools/kotlin-stdlib-1.5.0.jar',
    'tools/flatbuffers-java-23.5.26.jar'
)
$compilePath = (@(
    'tools/android-35.jar',
    'tools/libxposed-api-102.jar'
) + $dependencies) -join ';'

$sources = @(
    Get-ChildItem -LiteralPath src/main/java -Recurse -Filter *.java |
        ForEach-Object FullName
)
Invoke-Checked $javac (@(
    '--release','17','-encoding','UTF-8',
    '-cp',$compilePath,
    '-d','build/classes'
) + $sources)
Invoke-Checked $jar @('--create','--file','build/module.jar','-C','build/classes','.')

$d8Inputs = @('build/module.jar') + $dependencies
Invoke-Checked $java (@(
    '-cp','tools/r8-8.6.27.jar',
    'com.android.tools.r8.D8',
    '--min-api','29',
    '--lib','tools/android-35.jar',
    '--classpath','tools/libxposed-api-102.jar',
    '--output','build/dex'
) + $d8Inputs)

Invoke-Checked 'tools/aapt2.exe' @(
    'compile','--dir','src/main/res','-o','build/resources.zip'
)
Invoke-Checked 'tools/aapt2.exe' @(
    'link',
    '-I','tools/android-35.jar',
    '--manifest','src/main/AndroidManifest.xml',
    '-o','build/base.apk',
    'build/resources.zip'
)
Invoke-Checked 'python' @('tools/package_apk.py','package')

if (!(Test-Path -LiteralPath 'tools/gameboxvisionfix-local.p12')) {
    Invoke-Checked $keytool @(
        '-genkeypair',
        '-keystore','tools/gameboxvisionfix-local.p12',
        '-storetype','PKCS12',
        '-storepass','android',
        '-keypass','android',
        '-alias','gameboxvisionfix',
        '-keyalg','RSA',
        '-keysize','3072',
        '-validity','10000',
        '-dname','CN=GameBox Vision Fix'
    )
}

Invoke-Checked $java @(
    '--class-path','tools/apksig-8.6.1.jar',
    'tools/SignApk.java',
    'tools/gameboxvisionfix-local.p12',
    'build/unsigned.apk',
    'outputs/gamebox-vision-fix-v1.0.1.apk'
)
Invoke-Checked 'python' @('tools/package_apk.py','verify')
Invoke-Checked 'tools/aapt2.exe' @(
    'dump','badging','outputs/gamebox-vision-fix-v1.0.1.apk'
)
Get-FileHash -LiteralPath 'outputs/gamebox-vision-fix-v1.0.1.apk' -Algorithm SHA256
