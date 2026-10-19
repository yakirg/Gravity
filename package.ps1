# Builds a self-contained Windows app image (bundled Java runtime, no install needed) in dist\Gravity.
# Usage: powershell -File package.ps1   (needs a JDK 17+ with jpackage; set JAVA_HOME or have it on PATH)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
if ($env:JAVA_HOME) { $env:PATH = "$env:JAVA_HOME\bin;$env:PATH" }
$work = Join-Path $env:TEMP "gravity-pkg"
Remove-Item -Recurse -Force $work, dist -ErrorAction SilentlyContinue
New-Item -ItemType Directory "$work\classes", "$work\input\images" | Out-Null

javac -cp "lib\*" -processorpath (Get-ChildItem lib\lombok*.jar).FullName -d "$work\classes" src\*.java
if ($LASTEXITCODE) { throw "javac failed" }
Set-Content "$work\manifest.txt" "Main-Class: Launcher"
jar --create --file "$work\input\gravity.jar" --manifest "$work\manifest.txt" -C "$work\classes" .
if ($LASTEXITCODE) { throw "jar failed" }
Copy-Item lib\javafx-*-win.jar "$work\input"
Copy-Item images\earth.jpg "$work\input\images"

jpackage --type app-image --name Gravity --input "$work\input" --main-jar gravity.jar --main-class Launcher `
    --add-modules java.se,jdk.unsupported --dest dist
if ($LASTEXITCODE) { throw "jpackage failed" }
Remove-Item -Recurse -Force $work
Write-Host "Done: dist\Gravity\Gravity.exe (distribute the whole dist\Gravity folder)"
