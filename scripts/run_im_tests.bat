@echo off
echo ============================================
echo   P2P即时通讯系统 - 测试脚本
echo ============================================
echo.

REM 设置环境变量
set PROJECT_ROOT=%~dp0..
cd /d "%PROJECT_ROOT%"

echo 1. 清理项目...
call mvn clean -q
if %ERRORLEVEL% neq 0 (
    echo 错误: Maven清理失败
    pause
    exit /b 1
)
echo   清理完成
echo.

echo 2. 编译项目...
call mvn compile -q
if %ERRORLEVEL% neq 0 (
    echo 错误: Maven编译失败
    pause
    exit /b 1
)
echo   编译完成
echo.

echo 3. 运行单元测试...
echo   运行UserManager测试...
call mvn test -Dtest=javax.net.p2p.im.UserManagerTest -q
if %ERRORLEVEL% neq 0 (
    echo 警告: UserManager测试失败
) else (
    echo   UserManager测试通过
)

echo   运行IMCommand测试...
call mvn test -Dtest=javax.net.p2p.im.IMCommandTest -q
if %ERRORLEVEL% neq 0 (
    echo 警告: IMCommand测试失败
) else (
    echo   IMCommand测试通过
)

echo   运行ChatMessageProcessor测试...
call mvn test -Dtest=javax.net.p2p.im.ChatMessageProcessorTest -q
if %ERRORLEVEL% neq 0 (
    echo 警告: ChatMessageProcessor测试失败
) else (
    echo   ChatMessageProcessor测试通过
)
echo.

echo 4. 运行集成测试...
echo   注意: 集成测试可能需要服务器运行
echo   运行IMIntegration测试...
call mvn test -Dtest=javax.net.p2p.im.IMIntegrationTest -q
if %ERRORLEVEL% neq 0 (
    echo 警告: IMIntegration测试失败
) else (
    echo   IMIntegration测试通过
)
echo.

echo 5. 运行所有IM系统测试...
call mvn test -Dtest="javax.net.p2p.im.*Test" -q
if %ERRORLEVEL% neq 0 (
    echo 警告: 部分测试失败，请查看详细日志
) else (
    echo   所有IM系统测试通过
)
echo.

echo 6. 生成测试报告...
call mvn surefire-report:report -q
if %ERRORLEVEL% neq 0 (
    echo 警告: 测试报告生成失败
) else (
    echo   测试报告已生成到 target/site/surefire-report.html
)
echo.

echo ============================================
echo   测试完成
echo   查看详细报告: target/site/surefire-report.html
echo ============================================

pause