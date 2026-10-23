@echo off
REM Executa o Ulisses rapidamente no Windows (cmd/PowerShell ou duplo-clique).
REM   run.cmd            builda e SOBE tudo via Docker, espera pronto e abre o navegador
REM   run.cmd dev        modo desenvolvimento (abre backend e frontend em janelas separadas)
REM   run.cmd build      apenas builda backend (jar) e frontend (dist)
REM   run.cmd logs       acompanha os logs dos containers
REM   run.cmd stop       derruba os containers do Docker
setlocal
cd /d "%~dp0"
set MODE=%1
if "%MODE%"=="" set MODE=docker

if /i "%MODE%"=="docker" goto docker
if /i "%MODE%"=="up" goto docker
if /i "%MODE%"=="dev" goto dev
if /i "%MODE%"=="build" goto build
if /i "%MODE%"=="logs" goto logs
if /i "%MODE%"=="stop" goto stop
if /i "%MODE%"=="down" goto stop
goto usage

:docker
where docker >nul 2>nul || (echo Docker nao encontrado. Use: run.cmd dev & exit /b 1)
if not exist .env copy .env.example .env >nul
echo Buildando e subindo o Ulisses via Docker ^(o primeiro start baixa deps + modelos ~185MB^)...
docker compose up --build -d || (echo Falha ao subir. & exit /b 1)
echo Aguardando o ambiente ficar pronto...
for /l %%i in (1,1,150) do (
  curl -s -o nul --max-time 3 http://localhost:5173 >nul 2>nul && goto ready
  timeout /t 2 >nul
)
echo Nao respondeu a tempo. Veja: run.cmd logs
exit /b 1
:ready
echo Ambiente no ar! Frontend: http://localhost:5173  Backend: http://localhost:8080
echo Abrindo o navegador... ^(no 1o start os modelos ainda baixam por alguns minutos^)
start "" http://localhost:5173
echo Logs: run.cmd logs  ^|  Parar: run.cmd stop
goto :eof

:dev
call :detect_java
echo Subindo backend ^(:8080^) e frontend ^(:5173^) em janelas separadas...
start "Ulisses backend" cmd /k "cd /d %~dp0backend && mvnw.cmd spring-boot:run"
start "Ulisses frontend" cmd /k "cd /d %~dp0frontend && npm install && npm run dev"
echo Aguardando ficar pronto para abrir o navegador...
for /l %%i in (1,1,150) do (
  curl -s -o nul --max-time 3 http://localhost:5173 >nul 2>nul && goto devready
  timeout /t 2 >nul
)
:devready
start "" http://localhost:5173
echo Duas janelas foram abertas. Feche-as para encerrar.
goto :eof

:build
call :detect_java
pushd backend && call mvnw.cmd -DskipTests package && popd
pushd frontend && call npm install && call npm run build && popd
echo Pronto: backend\target\*.jar e frontend\dist\
goto :eof

:logs
docker compose logs -f
goto :eof

:stop
docker compose down
echo Containers encerrados.
goto :eof

:detect_java
if not "%JAVA_HOME%"=="" goto :eof
if exist "E:\androidStudio\jdk\jdk-21.0.11+10\bin\java.exe" set "JAVA_HOME=E:\androidStudio\jdk\jdk-21.0.11+10"
goto :eof

:usage
echo Uso: run.cmd [docker^|dev^|build^|logs^|stop]
echo   (vazio) ^| docker   Builda, sobe tudo, espera pronto e abre o navegador. Recomendado.
echo   dev                Desenvolvimento (janelas separadas) + abre o navegador.
echo   build              Builda backend e frontend, sem rodar.
echo   logs               Acompanha os logs dos containers.
echo   stop               Derruba os containers do Docker.
goto :eof
