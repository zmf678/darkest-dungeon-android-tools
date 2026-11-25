@echo off
chcp 65001 >nul
cd /d "%~dp0"
where python >nul 2>nul
if errorlevel 1 (
    echo [错误] 未检测到 Python 3.10+，请先安装 Python 并勾选 "Add python.exe to PATH"。
    pause
    exit /b 1
)
python run.py ui %*
if errorlevel 1 (
    pause
)
