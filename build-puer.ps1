param(
    [Parameter(Mandatory)][string]$Jdk,
    [Parameter(Mandatory)][string]$Sdk,
    [Parameter(Mandatory)][string]$Output
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$env:JAVA_HOME = (Resolve-Path -LiteralPath $Jdk).Path
$env:Path = "$env:JAVA_HOME/bin;$env:Path"
$Sdk = (Resolve-Path -LiteralPath $Sdk).Path
$Output = [IO.Path]::GetFullPath($Output)
$tools = "$Sdk/build-tools/35.0.0"
$android = "$Sdk/platforms/android-35/android.jar"
$build = Join-Path $PSScriptRoot ('work/build-puer-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force "$build/classes", "$build/generated", "$build/dex" | Out-Null
& "$tools/aapt2.exe" compile --dir "$PSScriptRoot/app/res" -o "$build/resources.zip"
& "$tools/aapt2.exe" link -I $android --manifest "$PSScriptRoot/app/AndroidManifest.xml" --java "$build/generated" --custom-package com.ledao.tester -A "$PSScriptRoot/app/assets" -o "$build/base.apk" "$build/resources.zip"
$sources = @(Get-ChildItem "$PSScriptRoot/app/src", "$build/generated" -Recurse -Filter '*.java' | ForEach-Object FullName)
Write-Output "Compiling $($sources.Count) Java source files from app/ and generated resources"
& "$env:JAVA_HOME/bin/javac.exe" --release 11 -encoding UTF-8 -cp $android -d "$build/classes" @sources
& "$env:JAVA_HOME/bin/jar.exe" cf "$build/classes.jar" -C "$build/classes" .
& "$tools/d8.bat" --lib $android --min-api 23 --output "$build/dex" "$build/classes.jar"
Push-Location "$build/dex"
try { & "$env:JAVA_HOME/bin/jar.exe" uf "$build/base.apk" classes.dex } finally { Pop-Location }
& "$tools/zipalign.exe" -f 4 "$build/base.apk" "$build/aligned.apk"
$keys = "$PSScriptRoot/work/keys"
New-Item -ItemType Directory -Force $keys | Out-Null
$keystore = "$keys/puer-local-test.p12"
if (!(Test-Path -LiteralPath $keystore)) {
    & "$env:JAVA_HOME/bin/keytool.exe" -genkeypair -alias local-test -keyalg RSA -keysize 2048 -validity 3650 -keystore $keystore -storepass android -dname 'CN=Puer Campus Development'
}
& "$tools/apksigner.bat" sign --ks $keystore --ks-pass pass:android --out $Output "$build/aligned.apk"
& "$tools/apksigner.bat" verify --verbose $Output
& "$tools/zipalign.exe" -c 4 $Output
Get-FileHash -LiteralPath $Output -Algorithm SHA256
