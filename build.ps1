param(
    [Parameter(Mandatory)][string]$Jdk,
    [Parameter(Mandatory)][string]$Sdk,
    [Parameter(Mandatory)][string]$Output
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$env:JAVA_HOME = (Resolve-Path -LiteralPath $Jdk).Path
$env:Path = "$env:JAVA_HOME/bin;$env:Path"
$sdkRoot = (Resolve-Path -LiteralPath $Sdk).Path
$outputFile = [IO.Path]::GetFullPath($Output)
$android = Join-Path $sdkRoot 'platforms/android-35/android.jar'
$tools = Join-Path $sdkRoot 'build-tools/35.0.0'
$build = Join-Path $PSScriptRoot '.build'
New-Item -ItemType Directory -Force $build, (Split-Path $outputFile) | Out-Null
$job = Join-Path $build ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force "$job/classes", "$job/generated", "$job/dex" | Out-Null

& "$tools/aapt2.exe" compile --dir "$PSScriptRoot/res" -o "$job/resources.zip"
& "$tools/aapt2.exe" link -I $android --manifest "$PSScriptRoot/AndroidManifest.xml" --java "$job/generated" -o "$job/base.apk" "$job/resources.zip"
$sources = @(Get-ChildItem -LiteralPath "$PSScriptRoot/src", "$job/generated" -Recurse -Filter '*.java' | ForEach-Object FullName)
& "$env:JAVA_HOME/bin/javac.exe" --release 11 -encoding UTF-8 -cp $android -d "$job/classes" @sources
& "$env:JAVA_HOME/bin/jar.exe" cf "$job/classes.jar" -C "$job/classes" .
& "$tools/d8.bat" --lib $android --min-api 23 --output "$job/dex" "$job/classes.jar"
& "$env:JAVA_HOME/bin/jar.exe" uf "$job/base.apk" -C "$job/dex" classes.dex
& "$tools/zipalign.exe" -f 4 "$job/base.apk" "$job/aligned.apk"

$keystore = Join-Path $build 'replay-development.p12'
if (!(Test-Path -LiteralPath $keystore)) {
    & "$env:JAVA_HOME/bin/keytool.exe" -genkeypair -alias replay-dev -keyalg RSA -keysize 2048 -validity 3650 -keystore $keystore -storepass android -dname 'CN=Puer Replay Development'
}
& "$tools/apksigner.bat" sign --ks $keystore --ks-key-alias replay-dev --ks-pass pass:android --v4-signing-enabled false --out $outputFile "$job/aligned.apk"
& "$tools/apksigner.bat" verify --verbose $outputFile
& "$tools/zipalign.exe" -c 4 $outputFile
Get-FileHash -LiteralPath $outputFile -Algorithm SHA256
