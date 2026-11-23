@rem Gradle Wrapper 启动脚本（精简版）
@rem gradle\wrapper\gradle-wrapper.jar 已内置：发行包 main 模块内嵌的 Gradle 官方原版，见 README.md 第 3 节
@echo off
setlocal
set APP_HOME=%~dp0
set CLASSPATH=%APP_HOME%gradle\wrapper\gradle-wrapper.jar
if defined JAVA_HOME (set JAVACMD=%JAVA_HOME%\bin\java.exe) else (set JAVACMD=java.exe)
if not exist "%CLASSPATH%" (
  echo 缺少 gradle-wrapper.jar，请从交付包恢复该文件（见 README.md 第 3 节） 1>&2
  exit /b 1
)
"%JAVACMD%" -Xmx64m -Xms64m %JAVA_OPTS% %GRADLE_OPTS% -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
endlocal
