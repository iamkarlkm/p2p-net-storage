@echo off
REM UDP性能监控仪表板启动脚本
REM 适用于Windows系统

echo =========================================
echo UDP性能监控仪表板启动脚本
echo =========================================
echo.

REM 设置工作目录
set WORK_DIR=%~dp0..
cd /d "%WORK_DIR%"

REM 检查Java环境
where java >nul 2>nul
if %ERRORLEVEL% neq 0 (
    echo [错误] 未找到Java环境，请安装JDK 11或更高版本
    pause
    exit /b 1
)

REM 检查Maven构建
if not exist "target\classes" (
    echo [警告] 项目未编译，正在尝试编译...
    call mvn clean compile -q
    if %ERRORLEVEL% neq 0 (
        echo [错误] 编译失败，请手动运行 mvn clean compile
        pause
        exit /b 1
    )
    echo [成功] 项目编译完成
)

REM 设置类路径
set CLASSPATH=target\classes
for %%f in (target\dependency\*.jar) do set CLASSPATH=!CLASSPATH!;%%f

REM 设置Java参数
set JAVA_OPTS=-Xms512m -Xmx1g -XX:+UseG1GC -XX:MaxGCPauseMillis=200

REM 设置监控参数
set MONITOR_OPTS=--start

REM 启动监控服务
echo [信息] 正在启动UDP性能监控服务...
echo [信息] HTTP端口: 8088
echo [信息] WebSocket端口: 8089
echo [信息] 监控仪表板: http://localhost:8088/monitor
echo.

java %JAVA_OPTS% -cp "%CLASSPATH%" ^
  javax.net.p2p.monitor.web.MonitorDashboardLauncher ^
  %MONITOR_OPTS%

if %ERRORLEVEL% neq 0 (
    echo [错误] 监控服务启动失败
    pause
    exit /b 1
)

echo.
echo =========================================
echo 监控服务已启动！
echo =========================================
echo 访问监控仪表板: http://localhost:8088/monitor
echo API接口文档: http://localhost:8088/monitor/api/udp/overview
echo WebSocket地址: ws://localhost:8089
echo 按Ctrl+C停止服务
echo =========================================
pause