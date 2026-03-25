@echo off
echo ============================================
echo   启动IM服务器
echo ============================================
echo.

REM 设置环境变量
set PROJECT_ROOT=%~dp0..
cd /d "%PROJECT_ROOT%"

echo 1. 编译项目...
call mvn clean compile -q
if %ERRORLEVEL% neq 0 (
    echo 错误: 项目编译失败
    pause
    exit /b 1
)
echo   编译成功
echo.

echo 2. 启动IM服务器 (端口6060)...
echo   服务器地址: 127.0.0.1:6060
echo   日志文件: logs/im-server.log
echo.

REM 创建日志目录
if not exist logs mkdir logs

REM 启动服务器
java -cp target/classes javax.net.p2p.im.IMServer

echo.
echo ============================================
echo   服务器已停止
echo ============================================
echo.
pause