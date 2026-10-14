@echo off
setlocal enabledelayedexpansion

echo ========================================================
echo   Elnea Assistant - Native Build Script (Zero-Gradle)
echo ========================================================

rem ---- Locate SDK / JDK / build-tools ----
if not defined ANDROID_HOME set "ANDROID_HOME=D:\HotDownloader\_tools\android-sdk"
if not defined JAVA_HOME  set "JAVA_HOME=D:\tool\jdk"

set "BT=%ANDROID_HOME%\build-tools\36.0.0"
if not exist "%BT%\aapt2.exe" set "BT=%ANDROID_HOME%\build-tools\35.0.0"
if not exist "%BT%\aapt2.exe" set "BT=%ANDROID_HOME%\build-tools\34.0.0"
if not exist "%BT%\aapt2.exe" goto :no_bt

set "PLATFORM=%ANDROID_HOME%\platforms\android-36\android.jar"
if not exist "%PLATFORM%" set "PLATFORM=%ANDROID_HOME%\platforms\android-34\android.jar"
if not exist "%PLATFORM%" goto :no_platform

set "AAPT2=%BT%\aapt2.exe"
set "D8=%BT%\d8.bat"
set "ZIPALIGN=%BT%\zipalign.exe"
set "APKSIGNER=%BT%\apksigner.bat"
set "JAVAC=%JAVA_HOME%\bin\javac.exe"
set "JAR=%JAVA_HOME%\bin\jar.exe"

set "KEYSTORE=..\tools\patch.keystore"
set "KS_PASS=elnea2026"
set "KS_ALIAS=elnea"
set "KEY_PASS=elnea2026"
set "OUT=..\03_patch_output\ElneaAssistant.apk"

echo [1/6] Compiling resources...
rmdir /s /q bin 2>nul
mkdir bin\gen bin\classes 2>nul
"%AAPT2%" compile --dir res -o bin\res.zip
if errorlevel 1 goto :fail

echo [2/6] Linking package...
"%AAPT2%" link -o bin\base.apk -I "%PLATFORM%" --manifest AndroidManifest.xml -A assets --java bin\gen bin\res.zip --auto-add-overlay
if errorlevel 1 goto :fail

echo [3/6] Compiling Java sources...
"%JAVAC%" -source 8 -target 8 -bootclasspath "%PLATFORM%" -encoding UTF-8 -Xlint:-options -nowarn -d bin\classes src\jp\co\althi\elnea\assistant\MainActivity.java bin\gen\jp\co\althi\elnea\assistant\R.java
if errorlevel 1 goto :fail

echo [4/6] Converting to Dalvik bytecode (D8)...
call "%D8%" --lib "%PLATFORM%" --min-api 25 --output bin\ bin\classes\jp\co\althi\elnea\assistant\*.class
if errorlevel 1 goto :fail

echo [5/6] Packaging dex into APK...
"%JAR%" uf bin\base.apk -C bin classes.dex
if errorlevel 1 goto :fail

echo [6/6] Aligning and signing...
"%ZIPALIGN%" -p -f 4 bin\base.apk bin\aligned.apk
"%APKSIGNER%" sign --ks "%KEYSTORE%" --ks-pass "pass:%KS_PASS%" --ks-key-alias "%KS_ALIAS%" --key-pass "pass:%KEY_PASS%" --out "%OUT%" bin\aligned.apk
if errorlevel 1 goto :fail

"%APKSIGNER%" verify "%OUT%"
echo.
echo [SUCCESS] APK generated at %OUT%
exit /b 0

:fail
echo [FAIL] Build stopped with error %errorlevel%.
exit /b 1

:no_bt
echo [ERROR] aapt2.exe not found under %ANDROID_HOME%\build-tools
exit /b 1

:no_platform
echo [ERROR] android.jar not found. Install platform android-36 or 34.
exit /b 1
