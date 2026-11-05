@echo off
rem ---------------------------------------------------------------------------
rem Lanzador de Maven sin instalación: la primera vez descarga Maven 3.9.9 en
rem %USERPROFILE%\.m2\wrapper y después lo reutiliza. Necesita un JDK 17 o
rem superior (basta con un JDK portable descomprimido y JAVA_HOME apuntando a él).
rem ---------------------------------------------------------------------------
setlocal
set "MAVEN_VERSION=3.9.9"
set "MAVEN_DIR=%USERPROFILE%\.m2\wrapper\apache-maven-%MAVEN_VERSION%"
set "MAVEN_ZIP=%USERPROFILE%\.m2\wrapper\apache-maven-%MAVEN_VERSION%-bin.zip"
set "MAVEN_URL=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/%MAVEN_VERSION%/apache-maven-%MAVEN_VERSION%-bin.zip"

if "%JAVA_HOME%"=="" (
  echo [mvnw] Falta JAVA_HOME. Apunta JAVA_HOME a un JDK 17 o superior.
  exit /b 1
)
if not exist "%MAVEN_DIR%\bin\mvn.cmd" (
  echo [mvnw] Descargando Maven %MAVEN_VERSION%...
  if not exist "%USERPROFILE%\.m2\wrapper" mkdir "%USERPROFILE%\.m2\wrapper"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "[Net.ServicePointManager]::SecurityProtocol='Tls12'; Invoke-WebRequest -UseBasicParsing -Uri '%MAVEN_URL%' -OutFile '%MAVEN_ZIP%'; Expand-Archive -Force -Path '%MAVEN_ZIP%' -DestinationPath '%USERPROFILE%\.m2\wrapper'"
  if errorlevel 1 (
    echo [mvnw] No se pudo descargar Maven. Revisa la conexion o el proxy.
    exit /b 1
  )
)
call "%MAVEN_DIR%\bin\mvn.cmd" %*
