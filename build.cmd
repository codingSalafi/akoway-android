@echo off
rem Builds the debug APK from WSL or a Windows prompt
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set GRADLE_OPTS=-Dorg.gradle.daemon.idletimeout=600000
cd /d D:\akoway-android
if not exist D:\akoway-android\gradlew.bat call "C:\Users\Dell\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat" wrapper --gradle-version 8.13 -q
call D:\akoway-android\gradlew.bat %* 
