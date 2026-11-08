# Runs a Gradle task with this machine's setup, without changing any global settings.
#   .\dev.ps1                  -> start the development client (runClient)
#   .\dev.ps1 runGameTestServer -> run the automated in-game tests
#   .\dev.ps1 build            -> build the mod jar into build\libs
param([string]$Task = "runClient")

# JDK 21 downloaded by IntelliJ
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\ms-21.0.11"
# Avast Web Shield re-signs HTTPS with a root that only the Windows certificate store trusts
$env:GRADLE_OPTS = "-Djavax.net.ssl.trustStoreType=Windows-ROOT"

& "$PSScriptRoot\gradlew.bat" $Task --console=plain
