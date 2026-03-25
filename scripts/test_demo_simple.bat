@echo off
echo ============================================
echo   演示系统简单测试
echo ============================================
echo.

echo 1. 检查文件结构...
if exist examples\GroupChatDemo.java (
    echo   GroupChatDemo.java: 存在
) else (
    echo   GroupChatDemo.java: 不存在
)

if exist examples\PerformanceDemo.java (
    echo   PerformanceDemo.java: 存在
) else (
    echo   PerformanceDemo.java: 不存在
)

if exist examples\IM_RUNNING_GUIDE.md (
    echo   IM_RUNNING_GUIDE.md: 存在
) else (
    echo   IM_RUNNING_GUIDE.md: 不存在
)

if exist scripts\run_demo.bat (
    echo   run_demo.bat: 存在
) else (
    echo   run_demo.bat: 不存在
)

if exist scripts\start_server.bat (
    echo   start_server.bat: 存在
) else (
    echo   start_server.bat: 不存在
)

echo.
echo 2. 检查Java环境...
java -version 2>nul
if %ERRORLEVEL% equ 0 (
    echo   Java环境: 正常
) else (
    echo   Java环境: 未安装
)

echo.
echo 3. 检查项目结构...
if exist src\main\java\javax\net\p2p\im\IMServer.java (
    echo   IMServer.java: 存在
) else (
    echo   IMServer.java: 不存在
)

if exist src\main\java\javax\net\p2p\im\ChatDemo.java (
    echo   ChatDemo.java: 存在
) else (
    echo   ChatDemo.java: 不存在
)

if exist src\main\java\javax\net\p2p\im\GroupManager.java (
    echo   GroupManager.java: 存在
) else (
    echo   GroupManager.java: 不存在
)

echo.
echo 4. 检查文档文件...
if exist IM_SYSTEM_README.md (
    echo   IM_SYSTEM_README.md: 存在
) else (
    echo   IM_SYSTEM_README.md: 不存在
)

if exist examples\IM_Demo_Guide.md (
    echo   IM_Demo_Guide.md: 存在
) else (
    echo   IM_Demo_Guide.md: 不存在
)

echo.
echo 5. 演示系统文件统计:
dir /b examples\*.java | find /c ":" >nul
set /a java_count=0
for /f %%i in ('dir /b examples\*.java 2^>nul ^| find /c /v ""') do set /a java_count=%%i
echo   Java示例文件: %java_count% 个

dir /b examples\*.md | find /c ":" >nul
set /a md_count=0
for /f %%i in ('dir /b examples\*.md 2^>nul ^| find /c /v ""') do set /a md_count=%%i
echo   Markdown文档: %md_count% 个

dir /b scripts\*.bat | find /c ":" >nul
set /a bat_count=0
for /f %%i in ('dir /b scripts\*.bat 2^>nul ^| find /c /v ""') do set /a bat_count=%%i
echo   Windows脚本: %bat_count% 个

dir /b scripts\*.sh | find /c ":" >nul
set /a sh_count=0
for /f %%i in ('dir /b scripts\*.sh 2^>nul ^| find /c /v ""') do set /a sh_count=%%i
echo   Linux脚本: %sh_count% 个

echo.
echo ============================================
echo   测试完成
echo ============================================
echo.
echo 演示系统包含:
echo   - %java_count% 个Java演示程序
echo   - %md_count% 个使用指南文档
echo   - %bat_count% 个Windows脚本
echo   - %sh_count% 个Linux/Unix脚本
echo.
echo 使用说明:
echo   1. 运行聊天演示: scripts\run_demo.bat
echo   2. 查看运行指南: examples\IM_RUNNING_GUIDE.md
echo   3. 查看演示指南: examples\IM_Demo_Guide.md
echo.

pause