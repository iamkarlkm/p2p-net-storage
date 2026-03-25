@echo off
echo ============================================
echo   P2P即时通讯系统 - 演示脚本
echo ============================================
echo.

REM 设置环境变量
set PROJECT_ROOT=%~dp0..
cd /d "%PROJECT_ROOT%"

echo 1. 检查环境...
call mvn --version >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo 错误: Maven未安装或未配置
    pause
    exit /b 1
)
echo   Maven环境检查通过
echo.

echo 2. 编译项目...
call mvn clean compile -q
if %ERRORLEVEL% neq 0 (
    echo 错误: 项目编译失败
    pause
    exit /b 1
)
echo   项目编译成功
echo.

echo 3. 选择演示类型:
echo    1. 点对点聊天演示
echo    2. 群聊功能演示
echo    3. 性能测试演示
echo    4. 完整系统演示
echo    5. API使用示例
echo    6. 退出
echo.

set /p choice=请选择演示类型 (1-6): 

if "%choice%"=="1" goto chat_demo
if "%choice%"=="2" goto group_demo
if "%choice%"=="3" goto performance_demo
if "%choice%"=="4" goto full_demo
if "%choice%"=="5" goto api_demo
if "%choice%"=="6" goto exit_demo
echo 无效的选择
pause
exit /b 1

:chat_demo
echo.
echo ============================================
echo   点对点聊天演示
echo ============================================
echo.
echo 启动聊天演示...
echo 注意：需要先启动IM服务器（端口6060）
echo.
set /p start_server=是否启动IM服务器？(y/N): 
if /i "%start_server%"=="y" goto start_server_first
goto run_chat_demo

:start_server_first
echo.
echo 启动IM服务器...
start "IM Server" java -cp target/classes javax.net.p2p.im.IMServer
echo 服务器启动中，等待5秒...
timeout /t 5 /nobreak >nul
echo.

:run_chat_demo
echo 运行聊天演示程序...
java -cp target/classes javax.net.p2p.im.ChatDemo
goto exit_demo

:group_demo
echo.
echo ============================================
echo   群聊功能演示
echo ============================================
echo.
echo 启动群聊演示...
echo 注意：需要先启动IM服务器（端口6060）
echo.
set /p start_server=是否启动IM服务器？(y/N): 
if /i "%start_server%"=="y" goto start_server_group
goto run_group_demo

:start_server_group
echo.
echo 启动IM服务器...
start "IM Server" java -cp target/classes javax.net.p2p.im.IMServer
echo 服务器启动中，等待5秒...
timeout /t 5 /nobreak >nul
echo.

:run_group_demo
echo 运行群聊演示程序...
java -cp "target/classes;." examples.GroupChatDemo
goto exit_demo

:performance_demo
echo.
echo ============================================
echo   性能测试演示
echo ============================================
echo.
echo 启动性能测试演示...
echo.
echo 选择性能测试类型:
echo    1. 快速测试 (5客户端，20消息)
echo    2. 标准测试 (10客户端，100消息)
echo    3. 压力测试 (50客户端，500消息)
echo    4. 自定义测试
echo    5. 查看性能优化建议
echo    6. 返回主菜单
echo.

set /p perf_choice=请选择 (1-6): 

if "%perf_choice%"=="1" (
    echo 运行快速性能测试...
    java -cp "target/classes;." examples.PerformanceDemo 5 20 30
)
if "%perf_choice%"=="2" (
    echo 运行标准性能测试...
    java -cp "target/classes;." examples.PerformanceDemo 10 100 60
)
if "%perf_choice%"=="3" (
    echo 运行压力测试...
    java -cp "target/classes;." examples.PerformanceDemo 50 500 120
)
if "%perf_choice%"=="4" (
    set /p clients=请输入客户端数量: 
    set /p messages=请输入每个客户端发送消息数: 
    set /p duration=请输入测试持续时间(秒): 
    echo 运行自定义性能测试...
    java -cp "target/classes;." examples.PerformanceDemo %clients% %messages% %duration%
)
if "%perf_choice%"=="5" (
    echo 显示性能优化建议...
    java -cp "target/classes;." examples.PerformanceDemo$OptimizationGuide
)
if "%perf_choice%"=="6" goto main_menu

goto exit_demo

:full_demo
echo.
echo ============================================
echo   完整系统演示
echo ============================================
echo.
echo 启动完整系统演示...
echo 1. 启动IM服务器
echo 2. 启动聊天客户端
echo 3. 演示点对点聊天
echo 4. 演示群聊功能
echo 5. 运行性能测试
echo 6. 显示系统状态
echo.

echo 步骤1: 启动IM服务器...
start "IM Server" java -cp target/classes javax.net.p2p.im.IMServer
echo 服务器启动中，等待3秒...
timeout /t 3 /nobreak >nul
echo.

echo 步骤2: 启动第一个聊天客户端 (用户1)...
start "Chat Client 1" java -cp target/classes javax.net.p2p.im.ChatDemo
echo 等待2秒...
timeout /t 2 /nobreak >nul
echo.

echo 步骤3: 启动第二个聊天客户端 (用户2)...
start "Chat Client 2" java -cp target/classes javax.net.p2p.im.ChatDemo
echo 等待2秒...
timeout /t 2 /nobreak >noble
echo.

echo 步骤4: 启动群聊演示...
start "Group Chat Demo" java -cp "target/classes;." examples.GroupChatDemo
echo 等待2秒...
timeout /t 2 /nobreak >nul
echo.

echo 步骤5: 运行快速性能测试...
start "Performance Test" java -cp "target/classes;." examples.PerformanceDemo 3 10 10
echo.

echo 完整系统演示已启动!
echo 请查看各个窗口进行交互演示
echo.
pause
goto exit_demo

:api_demo
echo.
echo ============================================
echo   API使用示例
echo ============================================
echo.
echo 选择API示例:
echo    1. 用户管理API示例
echo    2. 消息发送API示例
echo    3. 群聊API示例
echo    4. 客户端API示例
echo    5. 所有API示例
echo    6. 返回主菜单
echo.

set /p api_choice=请选择 (1-6): 

if "%api_choice%"=="1" (
    echo 运行用户管理API示例...
    echo TODO: 实现UserManager API示例
)
if "%api_choice%"=="2" (
    echo 运行消息发送API示例...
    echo TODO: 实现ChatMessageProcessor API示例
)
if "%api_choice%"=="3" (
    echo 运行群聊API示例...
    java -cp "target/classes;." examples.GroupChatDemo$GroupApiExamples
)
if "%api_choice%"=="4" (
    echo 运行客户端API示例...
    echo TODO: 实现ChatClient API示例
)
if "%api_choice%"=="5" (
    echo 运行所有API示例...
    echo 1. 群聊API示例...
    java -cp "target/classes;." examples.GroupChatDemo$GroupApiExamples
    echo.
    echo 2. 性能测试API示例...
    java -cp "target/classes;." examples.PerformanceDemo$BenchmarkExample
    echo.
    echo 3. 性能优化建议...
    java -cp "target/classes;." examples.PerformanceDemo$OptimizationGuide
)
if "%api_choice%"=="6" goto main_menu

echo.
pause
goto exit_demo

:main_menu
call %0
goto exit_demo

:exit_demo
echo.
echo ============================================
echo   演示完成
echo ============================================
echo.
echo 更多信息:
echo   - 查看演示指南: examples\IM_Demo_Guide.md
echo   - 运行测试: scripts\run_im_tests.bat
echo   - 查看项目文档: README.md
echo.

pause