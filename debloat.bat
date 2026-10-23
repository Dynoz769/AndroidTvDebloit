@echo off
rem Android TV Debloit - pelancar untuk Windows.
rem Klik dua kali fail ini untuk mula.
title Android TV Debloit
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0debloat.ps1"
pause
