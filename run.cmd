@echo off
REM Executa o Ulisses rapidamente no Windows (cmd/PowerShell ou duplo-clique).
REM   run.cmd            sobe TUDO via Docker (build + backend + frontend). Recomendado.
REM   run.cmd dev        modo desenvolvimento (abre backend e frontend em janelas separadas)
REM   run.cmd build      apenas builda backend (jar) e frontend (dist)
REM   run.cmd stop       derruba os containers do Docker
setlocal
cd /d "%~dp0"
set MODE=%1
if "%MODE%"=="" set MODE=docker

if /i "%MODE%"=="docker" goto docker
if /i "%MODE%"=="up" goto docker
if /i "%MODE%"=="dev" goto dev
if /i "%MODE%"=="build" goto build
if /i "%MODE%"=="stop" goto stop
if /i "%MODE%"=="down" goto stop
goto usage

:docker
where docker >nul 2>nul || (echo Docker nao encontrado. Instale o Docker ou use: run.cmd dev & exit /b 1)
if not exist .env copy .env.example .env >nul
echo Subindo o Ulisses via Docker ^(o primeiro start baixa deps + modelos ~185MB^)...
docker compose up --build
goto :eof

:dev
call :detect_java
echo Subindo backend ^(:8080^) e frontend ^(:5173^) em janelas separadas...
start "Ulisses backend" cmd /k "cd /d %~dp0backend && mvnw.cmd spring-boot:run"
start "Ulisses frontend" cmd /k "cd /d %~dp0frontend && npm install && npm run dev"
echo Duas janelas foram abertas. Feche-as para encerrar. Frontend: http://localhost:5173
goto :eof

:build
call :detect_java
pushd backend && call mvnw.cmd -DskipTests package && popd
pushd frontend && call npm install && call npm run build && popd
echo Pronto: backend\target\*.jar e frontend\dist\
goto :eof

:stop
docker compose down
goto :eof

:detect_java
if not "%JAVA_HOME%"=="" goto :eof
if exist "E:\androidStudio\jdk\jdk-21.0.11+10\bin\java.exe" set "JAVA_HOME=E:\androidStudio\jdk\jdk-21.0.11+10"
goto :eof

:usage
echo Uso: run.cmd [docker^|dev^|build^|stop]
echo   (vazio) ^| docker   Sobe tudo via Docker. Recomendado.
echo   dev                Desenvolvimento com hot reload (janelas separadas).
echo   build              Builda backend e frontend, sem rodar.
echo   stop               Derruba os containers do Docker.
goto :eof
