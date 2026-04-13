@echo off
title Folia Server

:: ============================================================
::  Configura la memoria aqui
:: ============================================================
set MIN_RAM=4G
set MAX_RAM=8G
set JAR=folia-1.21.11-14.jar

:: ============================================================
::  Ruta al ejecutable de Java 21 (OBLIGATORIO para este JAR)
::  Cambia esta ruta segun donde tengas instalado Java 21
:: ============================================================
set JAVA_BIN=C:\Program Files\Java\jdk-21\bin\java.exe

:: Si Java 21 ya esta en el PATH del sistema, comenta la linea
:: de arriba y descomenta la siguiente:
:: set JAVA_BIN=java

:: ============================================================
::  Flags optimizados (Aikar's Flags para Java 21 + G1GC)
:: ============================================================
"%JAVA_BIN%" ^
  -Xms%MIN_RAM% ^
  -Xmx%MAX_RAM% ^
  -XX:+UseG1GC ^
  -XX:+ParallelRefProcEnabled ^
  -XX:MaxGCPauseMillis=200 ^
  -XX:+UnlockExperimentalVMOptions ^
  -XX:+DisableExplicitGC ^
  -XX:+AlwaysPreTouch ^
  -XX:G1NewSizePercent=30 ^
  -XX:G1MaxNewSizePercent=40 ^
  -XX:G1HeapRegionSize=8M ^
  -XX:G1ReservePercent=20 ^
  -XX:G1HeapWastePercent=5 ^
  -XX:G1MixedGCCountTarget=4 ^
  -XX:InitiatingHeapOccupancyPercent=15 ^
  -XX:G1MixedGCLiveThresholdPercent=90 ^
  -XX:G1RSetUpdatingPauseTimePercent=5 ^
  -XX:SurvivorRatio=32 ^
  -XX:+PerfDisableSharedMem ^
  -XX:MaxTenuringThreshold=1 ^
  -XX:+UseStringDeduplication ^
  -XX:+OptimizeStringConcat ^
  -Dusing.aikars.flags=https://mcflags.emc.gs ^
  -Daikars.new.flags=true ^
  -jar %JAR% --nogui

echo.
echo El servidor se ha detenido. Presiona cualquier tecla para cerrar.
pause >nul
