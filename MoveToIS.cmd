@echo off
setlocal EnableExtensions DisableDelayedExpansion

set "PACKAGE=%~1"
set "IS_PACKAGES=%~2"
set "GIT_ROOT=%~3"
if not defined PACKAGE goto usage
if not defined IS_PACKAGES goto usage
if not defined GIT_ROOT set "GIT_ROOT=%USERPROFILE%\git"

rem Validate the package name before using it in filesystem paths.
set PACKAGE 2>nul | findstr /R /X /C:"PACKAGE=[A-Za-z0-9_][A-Za-z0-9_]*" >nul
if errorlevel 1 (
    echo ERROR: Expected a package name containing only letters, digits or underscores.
    exit /b 1
)

for %%I in ("%GIT_ROOT%\%PACKAGE%") do set "REPO=%%~fI"
set "TARGET=%REPO%\%PACKAGE%"
set "IS_LINK=%IS_PACKAGES%\%PACKAGE%"

where git.exe >nul 2>&1
if errorlevel 1 (
    echo ERROR: git.exe is unavailable in this Command Prompt.
    exit /b 1
)

if not exist "%IS_PACKAGES%\" (
    echo ERROR: Integration Server packages directory was not found.
    echo "%IS_PACKAGES%"
    exit /b 1
)

if not exist "%TARGET%\manifest.v3" (
    echo ERROR: Package manifest.v3 was not found in the inner Git folder.
    echo "%TARGET%"
    exit /b 1
)

set "EXPECTED_ROOT="
for /f "delims=" %%G in ('git -C "%TARGET%" rev-parse --show-toplevel 2^>nul') do set "EXPECTED_ROOT=%%G"
if /i not "%EXPECTED_ROOT:/=\%"=="%REPO%" (
    echo ERROR: The inner package folder did not resolve to the expected Git checkout.
    echo Expected "%REPO%"
    echo Git returned "%EXPECTED_ROOT%"
    exit /b 1
)

rem Check for an existing entry, including a junction whose target is missing.
dir /b /a "%IS_PACKAGES%\%PACKAGE%*" 2>nul | findstr /I /L /X /C:"%PACKAGE%" >nul
if not errorlevel 1 goto existing

echo Creating junction for %PACKAGE%...
mklink /J "%IS_LINK%" "%TARGET%"
if errorlevel 1 (
    echo ERROR: Windows could not create the junction.
    exit /b 1
)

:verify
if not exist "%IS_LINK%\manifest.v3" (
    echo ERROR: The package manifest could not be reached through the IS path.
    exit /b 1
)

set "ACTUAL_ROOT="
for /f "delims=" %%G in ('git -C "%IS_LINK%" rev-parse --show-toplevel 2^>nul') do set "ACTUAL_ROOT=%%G"
if /i not "%ACTUAL_ROOT%"=="%EXPECTED_ROOT%" (
    echo ERROR: Git did not resolve the expected checkout through the IS path.
    echo Expected "%EXPECTED_ROOT%"
    echo Git returned "%ACTUAL_ROOT%"
    exit /b 1
)

echo.
echo OK: The IS package path resolves to the original Git checkout.
echo IS path: "%IS_LINK%"
echo Git root: "%ACTUAL_ROOT%"
exit /b 0

:existing
echo An entry already exists at "%IS_LINK%".
dir /a:l "%IS_PACKAGES%\%PACKAGE%*"
goto verify

:usage
echo Usage: MoveToIS.cmd PackageName "IS packages directory" ["Git checkout root"]
exit /b 1
