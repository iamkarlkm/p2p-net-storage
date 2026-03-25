#!/bin/bash

echo "============================================"
echo "  P2P即时通讯系统 - 测试脚本"
echo "============================================"
echo ""

# 设置环境变量
PROJECT_ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$PROJECT_ROOT"

echo "1. 清理项目..."
mvn clean -q
if [ $? -ne 0 ]; then
    echo "错误: Maven清理失败"
    exit 1
fi
echo "   清理完成"
echo ""

echo "2. 编译项目..."
mvn compile -q
if [ $? -ne 0 ]; then
    echo "错误: Maven编译失败"
    exit 1
fi
echo "   编译完成"
echo ""

echo "3. 运行单元测试..."
echo "   运行UserManager测试..."
mvn test -Dtest=javax.net.p2p.im.UserManagerTest -q
if [ $? -ne 0 ]; then
    echo "警告: UserManager测试失败"
else
    echo "   UserManager测试通过"
fi

echo "   运行IMCommand测试..."
mvn test -Dtest=javax.net.p2p.im.IMCommandTest -q
if [ $? -ne 0 ]; then
    echo "警告: IMCommand测试失败"
else
    echo "   IMCommand测试通过"
fi

echo "   运行ChatMessageProcessor测试..."
mvn test -Dtest=javax.net.p2p.im.ChatMessageProcessorTest -q
if [ $? -ne 0 ]; then
    echo "警告: ChatMessageProcessor测试失败"
else
    echo "   ChatMessageProcessor测试通过"
fi
echo ""

echo "4. 运行集成测试..."
echo "   注意: 集成测试可能需要服务器运行"
echo "   运行IMIntegration测试..."
mvn test -Dtest=javax.net.p2p.im.IMIntegrationTest -q
if [ $? -ne 0 ]; then
    echo "警告: IMIntegration测试失败"
else
    echo "   IMIntegration测试通过"
fi
echo ""

echo "5. 运行所有IM系统测试..."
mvn test -Dtest="javax.net.p2p.im.*Test" -q
if [ $? -ne 0 ]; then
    echo "警告: 部分测试失败，请查看详细日志"
else
    echo "   所有IM系统测试通过"
fi
echo ""

echo "6. 生成测试报告..."
mvn surefire-report:report -q
if [ $? -ne 0 ]; then
    echo "警告: 测试报告生成失败"
else
    echo "   测试报告已生成到 target/site/surefire-report.html"
fi
echo ""

echo "============================================"
echo "   测试完成"
echo "   查看详细报告: target/site/surefire-report.html"
echo "============================================"