@echo off
rem Copies the source from WSL (where it is edited) to D:, then builds with Gradle there
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set GRADLE_OPTS=-Dorg.gradle.daemon.idletimeout=600000
robocopy "\\wsl.localhost\Ubuntu-24.04\home\codingsalafi\Dev\my-codes\akoway-android" D:\akoway-android /MIR /XD build .gradle .kotlin .git /NFL /NDL /NJH /NJS /NP >nul
cd /d D:\akoway-android
if not exist D:\akoway-android\gradlew.bat call "C:\Users\Dell\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat" wrapper --gradle-version 8.13 -q
call D:\akoway-android\gradlew.bat %* 
